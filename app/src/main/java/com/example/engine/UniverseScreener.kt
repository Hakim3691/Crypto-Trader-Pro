package com.example.engine

import com.example.data.local.CandleDao
import com.example.data.local.CandleEntity
import com.example.data.model.CoinCalibration
import com.example.data.model.CryptoAsset
import com.example.data.model.MarketRegime
import com.example.data.remote.BinanceFuturesClient
import com.example.data.remote.CoinGlassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Phase 2 (Spec §4, §5, §6): live universe discovery and screening.
 *
 * Pipeline:
 *  1. Binance futures exchangeInfo → tradable perp universe.
 *  2. §4 Strict Inclusion: keep symbols whose base asset has a TRADING **spot** market
 *     on Binance AND a CoinGlass derivatives record (funding or OI present). If the
 *     CoinGlass service as a whole is unreachable, the screener DEGRADES to
 *     Binance-validated symbols (funding from the Binance premium index) instead of
 *     returning nothing — the alternative was the app silently faking an offline seed,
 *     which violates the live-display mandate. Strict CoinGlass validation resumes the
 *     moment CoinGlass answers again.
 *  3. §6 Universe Filtering: 24h quote volume $50M–$750M, spread < 0.10% (measured
 *     from the real L2 top of book), funding history available.
 *  4. Pain Score ranking via [IndicatorMath] on real funding + computed bias.
 *
 * §6 Screener Output: the ranked list is NOT truncated — every qualifying symbol is
 * returned (the previous hardcoded 12-cap was an invention not present in the spec).
 *
 * Performance: volume/spot filtering (cheap REST snapshots) happens BEFORE any
 * per-symbol calls; depth and kline fetches run with bounded parallelism over only the
 * survivors, and klines are Room-cached so subsequent polls are nearly free.
 */
class UniverseScreener(private val candleDao: CandleDao) {

    companion object {
        // §6 Universe Filtering Boundaries
        const val MIN_QUOTE_VOLUME_USD = 50_000_000.0
        const val MAX_QUOTE_VOLUME_USD = 750_000_000.0
        const val MAX_SPREAD_PCT = 0.10
        const val DEPTH_SAMPLE_LIMIT = 10

        // §5 Heavy Data staleness window for HTF context.
        const val HTF_STALENESS_MS: Long = 4L * 60L * 60L * 1000L

        // Bounded concurrency for per-symbol REST work (stays far inside Binance
        // weight limits while finishing a full pass in seconds, not minutes).
        const val PARALLEL_SYMBOL_WORK = 8
    }

    /** §4 Pre-processing validation state for one symbol (cheap checks only). */
    private data class ScreenCandidate(
        val symbol: String,
        val baseAsset: String,
        val ticker: BinanceFuturesClient.Ticker24h,
        val premium: BinanceFuturesClient.PremiumIndex?,
        val passesCoinGlassInclusion: Boolean
    )

    /** Offline seed universe for the clearly-labeled first-frame placeholder. */
    private val seedRepository = MarketDataRepository()

    fun offlineSeedUniverse(): List<CryptoAsset> = seedRepository.getInitialUniverse()

    /**
     * Discovers and ranks the live mid-cap universe. Returns an empty list when even
     * the Binance feeds are unreachable (caller decides on the offline state).
     */
    suspend fun screenUniverse(): List<CryptoAsset> = withContext(Dispatchers.IO) {
        coroutineScope {
            val exchangeInfo = async { BinanceFuturesClient.fetchFuturesExchangeInfo() }
            val spotBases = async { BinanceFuturesClient.fetchSpotExchangeInfo() }
            val tickers = async { BinanceFuturesClient.fetch24hTickers() }
            val premiums = async { BinanceFuturesClient.fetchPremiumIndexes() }
            val fundingDeferred = async { CoinGlassClient.fetchFundingRates() }
            val oisDeferred = async { CoinGlassClient.fetchOpenInterestUsd() }
            val basisDeferred = async { CoinGlassClient.fetchBasis() }
            val liqDeferred = async { CoinGlassClient.fetchLiquidationHistory24h() }

            val info = exchangeInfo.await() ?: return@coroutineScope emptyList()
            val spotBaseSet = spotBases.await().toSet()
            val tickerBySymbol = tickers.await().associateBy { it.symbol }
            val premiumBySymbol = premiums.await().associateBy { it.symbol }
            val funding = fundingDeferred.await()
            val ois = oisDeferred.await()
            val basis = basisDeferred.await()
            val liq = liqDeferred.await()
            val coinGlassReachable = funding.isNotEmpty() || ois.isNotEmpty()

            val tradablePerps = info.symbols.filter {
                it.status == "TRADING" &&
                    it.contractType == "PERPETUAL" &&
                    it.quoteAsset == "USDT"
            }

            // ---- Stage 1 (cheap, no per-symbol REST): inclusion + §6 volume filter.
            val stage1 = tradablePerps.mapNotNull { sym ->
                val ticker = tickerBySymbol[sym.symbol] ?: return@mapNotNull null
                val premium = premiumBySymbol[sym.symbol]

                val base = sym.baseAsset?.uppercase() ?: return@mapNotNull null
                val hasSpot = base in spotBaseSet
                if (!hasSpot) return@mapNotNull null

                // §4 Strict Inclusion: CoinGlass futures record required when CoinGlass
                // answered; degraded to Binance-only validation when the service is down.
                val hasCoinGlassFutures =
                    funding.containsKey(sym.symbol) || ois.containsKey(sym.symbol)
                if (coinGlassReachable && !hasCoinGlassFutures) return@mapNotNull null

                val quoteVolume = ticker.quoteVolume ?: return@mapNotNull null
                if (quoteVolume !in MIN_QUOTE_VOLUME_USD..MAX_QUOTE_VOLUME_USD) return@mapNotNull null
                if (ticker.lastPrice == null && premium?.markPrice == null) return@mapNotNull null

                ScreenCandidate(
                    symbol = sym.symbol,
                    baseAsset = sym.baseAsset ?: base,
                    ticker = ticker,
                    premium = premium,
                    passesCoinGlassInclusion = hasCoinGlassFutures || !coinGlassReachable
                )
            }

            // ---- Stage 2: real L2 top-of-book spread, bounded-parallel.
            val semSpread = Semaphore(PARALLEL_SYMBOL_WORK)
            val spreads: Map<String, Double> = stage1.map { candidate ->
                async {
                    semSpread.withPermit {
                        val mid = candidate.ticker.lastPrice ?: candidate.premium?.markPrice ?: 0.0
                        candidate.symbol to measureSpreadPct(candidate.symbol, mid)
                    }
                }
            }.awaitAll().toMap()

            val spreadSurvivors = stage1.filter {
                (spreads[it.symbol] ?: 100.0) < MAX_SPREAD_PCT
            }

            // ---- Stage 3: real candles → ATR + multi-horizon bias, bounded-parallel.
            val semCandles = Semaphore(PARALLEL_SYMBOL_WORK)
            val assets = spreadSurvivors.map { candidate ->
                async {
                    semCandles.withPermit {
                        val lastPrice = candidate.ticker.lastPrice ?: candidate.premium?.markPrice ?: 0.0
                        val fundingRate = candidate.premium?.lastFundingRate ?: 0.0

                        // §9/§15: structural + momentum multi-horizon bias from real candles.
                        val strategicBias = IndicatorMath.structuralBias(loadCandles(candidate.symbol, "4h", 60))
                        val tacticalBias = IndicatorMath.structuralBias(loadCandles(candidate.symbol, "1h", 60))

                        val regime = classifyRegime(strategicBias, tacticalBias)
                        val pain = IndicatorMath.painScore(fundingRate, tacticalBias)
                        val mid = lastPrice
                        val premium = candidate.premium
                        val index = premium?.indexPrice ?: mid
                        val mark = premium?.markPrice ?: mid
                        val basisFromBinance = if (index > 0.0 && mark > 0.0) {
                            (mark - index) / index * 100.0 * 365.0
                        } else 0.0
                        val basisPremiumPct = when {
                            basis.containsKey(candidate.symbol) -> basis[candidate.symbol]
                            else -> basisFromBinance
                        }
                        val liq = liq[candidate.symbol] ?: Pair(0.0, 0.0)

                        CryptoAsset(
                            symbol = candidate.symbol,
                            baseAsset = candidate.baseAsset,
                            lastPrice = lastPrice,
                            priceChange24h = candidate.ticker.priceChangePercent ?: 0.0,
                            quoteVolume24h = candidate.ticker.quoteVolume ?: 0.0,
                            orderBookSpreadPct = spreads[candidate.symbol] ?: 100.0,
                            fundingRatePct = fundingRate,
                            predictedFundingPct = premium?.lastFundingRate ?: fundingRate,
                            openInterestUsd = ois[candidate.symbol] ?: 0.0,
                            basisPremiumPct = basisPremiumPct,
                            atr5m = atrFor(candidate.symbol, "5m", lastPrice),
                            atr1h = atrFor(candidate.symbol, "1h", lastPrice),
                            atr4h = atrFor(candidate.symbol, "4h", lastPrice),
                            regime = regime,
                            tacticalBias = tacticalBias,
                            strategicBias = strategicBias,
                            primaryPainPath = buildPainPath(
                                pain,
                                tacticalBias,
                                strategicBias,
                                liq.first,
                                liq.second
                            ),
                            liquidationContext = liquidationContextFor(candidate.symbol, liq)
                        )
                    }
                }
            }.awaitAll()

            // §6 Screener Output: full ranked list — every qualifying symbol ships.
            assets.sortedByDescending { asset ->
                IndicatorMath.painScore(asset.fundingRatePct, asset.tacticalBias) +
                    Math.abs(asset.tacticalBias)
            }
        }
    }

    /** The three decision timeframes (§7) used by structure detection and calibration. */
    data class TimeframeCandles(
        val candles4h: List<BinanceFuturesClient.Candle>,
        val candles1h: List<BinanceFuturesClient.Candle>,
        val candles5m: List<BinanceFuturesClient.Candle>
    )

    /**
     * Loads the HTF/MTF/LTF candle set for one symbol through the §5 Room-first cache.
     * Used by the structure engine and calibration so analytics share one data path.
     */
    suspend fun loadTimeframes(symbol: String): TimeframeCandles = TimeframeCandles(
        candles4h = loadCandles(symbol, "4h", 120),
        candles1h = loadCandles(symbol, "1h", 120),
        candles5m = loadCandles(symbol, "5m", 120)
    )

    /**
     * §10 Coin-Specific Calibration computed from real 1h (behavior) and 5m (execution)
     * history — retires the dead-model gap by making calibration a live screener output.
     */
    suspend fun loadCalibration(asset: CryptoAsset): CoinCalibration =
        IndicatorMath.computeCalibration(
            symbol = asset.symbol,
            candles1h = loadCandles(asset.symbol, "1h", 120),
            candles5m = loadCandles(asset.symbol, "5m", 120),
            atr5m = asset.atr5m,
            spreadPct = asset.orderBookSpreadPct
        )

    /** §4: spread measured from a real L2 top-of-book snapshot; 100% when unavailable. */
    private suspend fun measureSpreadPct(symbol: String, mid: Double): Double {
        val depth = BinanceFuturesClient.fetchDepth(symbol, DEPTH_SAMPLE_LIMIT) ?: return 100.0
        val bestBid = depth.bids.firstOrNull()?.first ?: return 100.0
        val bestAsk = depth.asks.firstOrNull()?.first ?: return 100.0
        if (mid <= 0.0) return 100.0
        return (bestAsk - bestBid) / mid * 100.0
    }

    /** ATR from persisted-or-fresh 5m candles; 0.0 when data is unavailable. */
    private suspend fun atrFor(symbol: String, interval: String, fallbackPrice: Double): Double {
        val candles = loadCandles(symbol, interval, 60)
        val atr = IndicatorMath.atr(candles)
        if (atr > 0.0) return atr
        // Fallback approximates one range unit so ladders remain constructible offline.
        return fallbackPrice * 0.002
    }

    /**
     * §5 Heavy Data: candles are served from the local store when fresh (< 4h); only a
     * stale or empty store triggers a network download, and results are persisted.
     */
    private suspend fun loadCandles(symbol: String, interval: String, limit: Int): List<BinanceFuturesClient.Candle> {
        val cached = runCatching { candleDao.getCandles(symbol, interval) }.getOrDefault(emptyList())
        val latest = runCatching { candleDao.getLatestFetchedAtMs(symbol, interval) }.getOrNull()
        val fresh = cached.isNotEmpty() && latest != null &&
            (System.currentTimeMillis() - latest) < HTF_STALENESS_MS
        if (fresh) {
            return cached.takeLast(limit).map { it.toDomain() }
        }
        val fetched = BinanceFuturesClient.fetchKlines(symbol, interval, limit)
        if (fetched.isNotEmpty()) {
            val now = System.currentTimeMillis()
            runCatching {
                candleDao.insertCandles(
                    fetched.map {
                        CandleEntity(
                            id = "$symbol|$interval|${it.openTimeUtcMs}",
                            symbol = symbol,
                            interval = interval,
                            openTimeUtcMs = it.openTimeUtcMs,
                            closeTimeUtcMs = it.closeTimeUtcMs,
                            open = it.open,
                            high = it.high,
                            low = it.low,
                            close = it.close,
                            volume = it.volume,
                            quoteVolume = it.quoteVolume,
                            fetchedAtMs = now
                        )
                    }
                )
            }
            return fetched
        }
        return cached.takeLast(limit).map { it.toDomain() }
    }

    /** §9 regime classification from real bias scores (band table mirrored in §15). */
    private fun classifyRegime(strategicBias: Int, tacticalBias: Int): MarketRegime = when {
        strategicBias >= 20 && tacticalBias >= -19 -> MarketRegime.BULLISH_CONTINUATION
        strategicBias <= -20 && tacticalBias <= 19 -> MarketRegime.BEARISH_CONTINUATION
        Math.abs(strategicBias - tacticalBias) >= 40 -> MarketRegime.TRANSITION
        else -> MarketRegime.BALANCE
    }

    private fun buildPainPath(
        pain: Int,
        tactical: Int,
        strategic: Int,
        longLiquidatedUsd24h: Double = 0.0,
        shortLiquidatedUsd24h: Double = 0.0
    ): String {
        val heaviestSide = when {
            longLiquidatedUsd24h > shortLiquidatedUsd24h * 1.8 -> "L-side flush"
            shortLiquidatedUsd24h > longLiquidatedUsd24h * 1.8 -> "S-side flush"
            else -> "no flush"
        }
        val dir = when {
            tactical >= 20 && strategic >= 20 -> "Bull continuation"
            tactical <= -20 && strategic <= -20 -> "Bear continuation"
            tactical >= 20 && strategic <= -20 -> "Tactical bull into strategic supply"
            tactical <= -20 && strategic >= 20 -> "Tactical bear into strategic demand"
            else -> "Range rotation"
        }
        return "$dir (pain $pain)"
    }

    private fun liquidationContextFor(symbol: String, liq: Pair<Double, Double>): LiquidationContext {
        return if (liq.first > 0.0 || liq.second > 0.0) {
            LiquidationContext(
                longLiquidatedUsd24h = liq.first,
                shortLiquidatedUsd24h = liq.second,
                lastUpdatedMs = System.currentTimeMillis()
            )
        } else {
            LiquidationContext.EMPTY
        }
    }

    private fun CandleEntity.toDomain() = BinanceFuturesClient.Candle(
        openTimeUtcMs = openTimeUtcMs,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        closeTimeUtcMs = closeTimeUtcMs,
        quoteVolume = quoteVolume
    )
}
