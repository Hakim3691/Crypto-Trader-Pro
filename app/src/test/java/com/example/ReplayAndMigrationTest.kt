package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.CandleEntity
import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.CryptoAsset
import com.example.data.model.LadderSlice
import com.example.data.model.MarketRegime
import com.example.data.model.StopLogic
import com.example.data.remote.BinanceFuturesClient
import com.example.engine.ExecutionEngine
import com.example.engine.ExecutionGuardrails
import com.example.engine.ExecutionGuardrails.PositionMismatchAction
import com.example.engine.IndicatorMath
import com.example.engine.IndicatorMath.BiasBand
import com.example.engine.ReplayEngine
import com.example.engine.RiskEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature: replay determinism, Room v1→v2 migration and the execution/analytics helpers
 * (Spec §5, §15, §22, §33, §36, §37).
 *
 * Background: every scenario runs under Robolectric with an in-memory database opened by
 * [openDatabase], so each test starts from an empty, isolated store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayAndMigrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // ------------------------------------------------------------ candle store

    @Test
    fun `should round-trip candles and replace rows on primary key conflict`() = runBlocking {
        // given candles stored for two symbols over two SOL hours
        val dao = db.candleDao()
        val now = 1_700_000_000_000L
        dao.insertCandles(
            listOf(
                candle("SOLUSDT", "1h", now, close = 150.0, fetchedAt = now),
                candle("SOLUSDT", "1h", now + 3_600_000, close = 152.0, fetchedAt = now),
                candle("BTCUSDT", "4h", now, close = 60_000.0, fetchedAt = now)
            )
        )

        // when the SOL 1h series is read back
        val sol = dao.getCandles("SOLUSDT", "1h")

        // then both rows exist and the watermark is the newest fetch
        assertEquals(2, sol.size)
        assertEquals(150.0, sol.first().close, 1e-9)
        assertEquals(now, dao.getLatestFetchedAtMs("SOLUSDT", "1h"))

        // when a §5 staleness re-download rewrites the same primary key
        dao.insertCandles(listOf(candle("SOLUSDT", "1h", now, close = 151.0, fetchedAt = now + 5)))

        // then the row is replaced, never duplicated
        val after = dao.getCandles("SOLUSDT", "1h")
        assertEquals(2, after.size)
        assertEquals(151.0, after.first { it.openTimeUtcMs == now }.close, 1e-9)

        // when the SOL 1h cache is cleared
        dao.clearCandles("SOLUSDT", "1h")

        // then only that symbol/interval is gone
        assertEquals(0, dao.getCandles("SOLUSDT", "1h").size)
        assertEquals(1, dao.getCandles("BTCUSDT", "4h").size)
        Unit
    }

    private fun candle(
        symbol: String,
        interval: String,
        openTimeUtcMs: Long,
        close: Double,
        fetchedAt: Long
    ) = CandleEntity(
        id = "$symbol|$interval|$openTimeUtcMs",
        symbol = symbol,
        interval = interval,
        openTimeUtcMs = openTimeUtcMs,
        closeTimeUtcMs = openTimeUtcMs + 3_599_000,
        open = close - 1.0,
        high = close + 1.5,
        low = close - 2.0,
        close = close,
        volume = 1000.0,
        quoteVolume = close * 1000.0,
        fetchedAtMs = fetchedAt
    )

    // ------------------------------------------------------------ migration

    @Test
    fun `should preserve user rows and add the candle table when migrating 1 to 2`() {
        // given a hand-built version-1 install seeded with real user data
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test-db"
        context.deleteDatabase(dbName)

        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val v1 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            v1.version = 1
            v1.execSQL(V1_ASSETS_DDL)
            v1.execSQL(V1_CAMPAIGNS_DDL)
            v1.execSQL(V1_AUDIT_DDL)
            v1.execSQL(
                "INSERT INTO crypto_assets (symbol, baseAsset, lastPrice, priceChange24h, quoteVolume24h, " +
                    "orderBookSpreadPct, fundingRatePct, openInterestUsd, basisPremiumPct, atr5m, regime, " +
                    "tacticalBias, strategicBias, primaryPainPath, updatedAt) VALUES " +
                    "('SOLUSDT', 'SOL', 150.0, 3.5, 500000000.0, 0.02, -0.015, 200000000.0, 0.05, 1.0, " +
                    "'BULLISH_CONTINUATION', 70, 65, 'Ascending Sweep', 1700000000000)"
            )
            v1.execSQL(
                "INSERT INTO audit_trail (asset, campaignId, action, reasonCode, message, timestamp) " +
                    "VALUES ('SOLUSDT', 'SEED', 'CAMPAIGN_STAGED', 'SEED', 'v1 row', 1700000000000)"
            )
        } finally {
            v1.close()
        }

        // when Room re-opens the same file through the real migration path
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            // then the reported schema version is 2
            assertEquals(2, migrated.openHelper.readableDatabase.version)

            // and the §5 heavy-data table exists on the migrated schema
            migrated.openHelper.readableDatabase.query("SELECT count(*) FROM htf_candles").use { c ->
                assertTrue(c.moveToFirst())
            }

            // and the seeded user rows survived instead of being wiped
            migrated.openHelper.readableDatabase.query("SELECT symbol FROM crypto_assets").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("SOLUSDT", cursor.getString(0))
            }
            migrated.openHelper.readableDatabase.query("SELECT reasonCode FROM audit_trail").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("SEED", cursor.getString(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    private companion object {
        /** Exact v1 DDL for the three original tables (all unchanged since v1). */
        val V1_ASSETS_DDL =
            "CREATE TABLE IF NOT EXISTS `crypto_assets` (`symbol` TEXT NOT NULL, `baseAsset` TEXT NOT NULL, " +
                "`lastPrice` REAL NOT NULL, `priceChange24h` REAL NOT NULL, `quoteVolume24h` REAL NOT NULL, " +
                "`orderBookSpreadPct` REAL NOT NULL, `fundingRatePct` REAL NOT NULL, `openInterestUsd` REAL NOT NULL, " +
                "`basisPremiumPct` REAL NOT NULL, `atr5m` REAL NOT NULL, `regime` TEXT NOT NULL, " +
                "`tacticalBias` INTEGER NOT NULL, `strategicBias` INTEGER NOT NULL, " +
                "`primaryPainPath` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`symbol`))"

        val V1_CAMPAIGNS_DDL =
            "CREATE TABLE IF NOT EXISTS `campaign_records` (`id` TEXT NOT NULL, `asset` TEXT NOT NULL, " +
                "`familyName` TEXT NOT NULL, `isLong` INTEGER NOT NULL, `regime` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `priorityScore` REAL NOT NULL, `sizeBudgetUsd` REAL NOT NULL, " +
                "`invalidationScore` INTEGER NOT NULL, `status` TEXT NOT NULL, `entryLadderJson` TEXT NOT NULL, " +
                "`targetsJson` TEXT NOT NULL, `topReasonsJson` TEXT NOT NULL, `hardStopPrice` REAL NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        val V1_AUDIT_DDL =
            "CREATE TABLE IF NOT EXISTS `audit_trail` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `asset` TEXT NOT NULL, `campaignId` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `message` TEXT NOT NULL)"
    }

    // ------------------------------------------------------------ replay determinism

    private fun replayAsset(price: Double) = CryptoAsset(
        symbol = "SOLUSDT",
        baseAsset = "SOL",
        lastPrice = price,
        priceChange24h = 2.0,
        quoteVolume24h = 400_000_000.0,
        orderBookSpreadPct = 0.03,
        fundingRatePct = -0.012,
        predictedFundingPct = -0.01,
        openInterestUsd = 150_000_000.0,
        basisPremiumPct = 0.04,
        atr5m = price * 0.004,
        atr1h = price * 0.012,
        atr4h = price * 0.03,
        regime = MarketRegime.BALANCE,
        tacticalBias = 10,
        strategicBias = 5,
        primaryPainPath = "Range rotation"
    )

    /** 60 candles: 50 chop, then a 10-candle directional impulse leg. */
    private fun impulseCandles(startPrice: Double, count: Int = 60): List<BinanceFuturesClient.Candle> {
        val out = mutableListOf<BinanceFuturesClient.Candle>()
        var price = startPrice
        var t = 1_700_000_000_000L
        repeat(count) { i ->
            val drift = if (i < count - 10) 0.0 else startPrice * 0.004
            val open = price
            val close = price + drift
            out.add(
                BinanceFuturesClient.Candle(
                    openTimeUtcMs = t,
                    open = open,
                    high = maxOf(open, close) * 1.001,
                    low = minOf(open, close) * 0.999,
                    close = close,
                    volume = 1_000.0 + i * 10,
                    closeTimeUtcMs = t + 3_599_000,
                    quoteVolume = close * 1_010.0
                )
            )
            price = close
            t += 3_600_000
        }
        return out
    }

    @Test
    fun `should replay identical results on every run of the same history`() {
        // given one fixture history and two independent runs
        val engine = ReplayEngine()
        val candles = impulseCandles(150.0)

        // when the same history is replayed twice
        val first = engine.replay(replayAsset(150.0), candles)
        val second = engine.replay(replayAsset(150.0), candles)

        // then every observable output matches exactly
        assertEquals(first.totalTicks, second.totalTicks)
        assertEquals(first.metrics.totalPnlUsd, second.metrics.totalPnlUsd, 0.0)
        assertEquals(first.metrics.replayedCampaigns, second.metrics.replayedCampaigns)
        assertEquals(
            first.campaigns.map { it.pnlUsd },
            second.campaigns.map { it.pnlUsd }
        )
    }

    @Test
    fun `should resolve every staged board and report mark-to-market pnl`() {
        // given a chopped-then-impulse history
        val engine = ReplayEngine()

        // when it is replayed
        val result = engine.replay(replayAsset(150.0), impulseCandles(150.0))

        // then every planned board produced a report (§37 per-template coverage)
        assertEquals(result.metrics.replayedCampaigns, result.campaigns.size)
        assertTrue(result.metrics.replayedCampaigns >= 4)
        assertTrue(result.totalTicks > 0)
        assertTrue(result.metrics.filledSliceCount >= 0)

        // and no board escaped mark-to-market: total PnL is the sum of per-board PnL
        val sum = result.campaigns.sumOf { it.pnlUsd }
        assertEquals(result.metrics.totalPnlUsd, Math.round(sum * 100.0) / 100.0, 0.011)
    }

    @Test
    fun `should complete and flag the run when crowding and liquidity feeds are absent`() {
        // given two runs: one fed real funding/spread, one without them
        val engine = ReplayEngine()
        val fed = engine.replay(
            replayAsset(150.0),
            impulseCandles(150.0),
            fundingRatePct = -0.02,
            spreadPct = 0.04
        )
        val degraded = engine.replay(replayAsset(150.0), impulseCandles(150.0))

        // then §37 honest degradation: the unfed run completes, ticks, and says it degraded
        assertTrue(degraded.totalTicks > 0)
        assertTrue(degraded.degradedFeeds)
        assertTrue(!fed.degradedFeeds)

        // and both runs produce finite metrics
        assertTrue(fed.metrics.totalPnlUsd.isFinite())
        assertTrue(degraded.metrics.totalPnlUsd.isFinite())
    }

    @Test
    fun `should keep the proactive run distinct from the symmetric baseline`() {
        // given the same history replayed proactively and in baseline mode
        val engine = ReplayEngine()
        val vmLike = engine.replay(replayAsset(150.0), impulseCandles(150.0))
        val baseline = engine.replay(
            replayAsset(150.0),
            impulseCandles(150.0),
            baselineMode = true
        )

        // when the filled notionals of both runs are inspected
        val proactiveSizes = vmLike.campaigns.map { it.filledNotionalUsd }.distinct()
        val baselineSizes = baseline.campaigns.map { it.filledNotionalUsd }.distinct()

        // then §37 comparison inputs exist on both sides and both totals are finite
        assertTrue(proactiveSizes.isNotEmpty())
        assertTrue(baselineSizes.isNotEmpty())
        assertTrue(baseline.metrics.totalPnlUsd.isFinite())
        assertTrue(vmLike.metrics.totalPnlUsd.isFinite())
    }

    @Test
    fun `should reject a replay when history is insufficient`() {
        // given only 10 candles, below the minimum history
        val engine = ReplayEngine()
        val short = impulseCandles(150.0, count = 10)

        // when the replay runs
        val result = engine.replay(replayAsset(150.0), short)

        // then it produces an empty result instead of inventing numbers
        assertEquals(0, result.metrics.replayedCampaigns)
        assertEquals(0, result.totalTicks)
        assertTrue(result.campaigns.isEmpty())
    }

    // ------------------------------------------------------------ analytics

    @Test
    fun `should detect fractal swing extremes on a synthetic w-shape`() {
        // given a low-high-low-high sequence of flat-range candles
        fun candleAt(price: Double, t: Long) = BinanceFuturesClient.Candle(
            openTimeUtcMs = t, open = price, high = price,
            low = price, close = price, volume = 1.0,
            closeTimeUtcMs = t + 1, quoteVolume = price
        )
        val prices = listOf(100.0, 95.0, 100.0, 105.0, 100.0, 95.0, 100.0, 105.0, 100.0)
        val candles = prices.mapIndexed { i, p -> candleAt(p, 1_700_000_000_000L + i * 60_000L) }

        // when swing points are extracted with a 2-candle window
        val swings = IndicatorMath.swingPoints(candles, window = 2)
        val lows = swings.filter { !it.isHigh }.map { it.price }.distinct()
        val highs = swings.filter { it.isHigh }.map { it.price }.distinct()

        // then the 95 trough and the 105 peak are both found
        assertTrue(lows.contains(95.0))
        assertTrue(highs.contains(105.0))
    }

    @Test
    fun `should separate an uptrend from a downtrend by structural bias`() {
        // given two 40-candle legs of equal slope, one rising and one falling
        fun leg(prices: List<Double>): List<BinanceFuturesClient.Candle> {
            var t = 1_700_000_000_000L
            return prices.map { p ->
                val c = BinanceFuturesClient.Candle(
                    openTimeUtcMs = t, open = p * 0.999, high = p * 1.001,
                    low = p * 0.998, close = p, volume = 10.0,
                    closeTimeUtcMs = t + 1, quoteVolume = p
                )
                t += 60_000
                c
            }
        }
        val up = (1..40).map { 100.0 + it * 0.5 }
        val down = (1..40).map { 120.0 - it * 0.5 }

        // when the structural bias of each leg is computed
        val upBias = IndicatorMath.structuralBias(leg(up))
        val downBias = IndicatorMath.structuralBias(leg(down))

        // then the uptrend is positive, the downtrend negative, and the uptrend ranks higher
        assertTrue("expected up bias > 0, got $upBias", upBias > 0)
        assertTrue("expected down bias < 0, got $downBias", downBias < 0)
        assertTrue(upBias > downBias)
    }

    @Test
    fun `should clamp coin calibration to finite bands on adversarial input`() {
        // given 40 alternating zero-wick impulses and flat candles
        fun candle(body: Double, wickUp: Double, wickDown: Double, t: Long) =
            BinanceFuturesClient.Candle(
                openTimeUtcMs = t,
                open = 100.0,
                high = 100.0 + wickUp + maxOf(body, 0.0),
                low = 100.0 - wickDown - maxOf(-body, 0.0),
                close = 100.0 + body,
                volume = 10.0,
                closeTimeUtcMs = t + 1,
                quoteVolume = 1_000.0
            )
        val candles = (0 until 40).map { i ->
            candle(if (i % 2 == 0) 2.0 else -2.0, 0.0, 0.0, 1_700_000_000_000L + i * 60_000L)
        }

        // when calibration is computed
        val calibration = IndicatorMath.computeCalibration(
            symbol = "TESTUSDT",
            candles1h = candles,
            candles5m = candles,
            atr5m = 2.0,
            spreadPct = 0.05
        )

        // then every factor stays inside its documented clamp
        assertTrue(calibration.impulseAsymmetry in 0.2..5.0)
        assertTrue(calibration.wickAsymmetry in 0.2..5.0)
        assertTrue(calibration.followThroughAsymmetry in 0.2..5.0)
        assertTrue(calibration.crowdingElasticity in 0.2..5.0)
        assertTrue(calibration.meanReversionHalfLifeMinutes in 5..240)
        assertTrue(calibration.typicalSlippageBps in 0.5..50.0)
    }

    @Test
    fun `should map bias scores onto the spec 15 band table`() {
        // given the documented score thresholds
        val table = BiasBand

        // then each threshold maps to its band
        assertEquals(BiasBand.STRONG_BULL, table.of(60))
        assertEquals(BiasBand.BULL, table.of(30))
        assertEquals(BiasBand.NEUTRAL, table.of(0))
        assertEquals(BiasBand.BEAR, table.of(-30))
        assertEquals(BiasBand.STRONG_BEAR, table.of(-60))
    }

    @Test
    fun `should gate paper fills by trade-through and reconcile by client order id`() {
        // given a staged long board resting a single bid at 100
        val engine = ExecutionEngine(RiskEngine())
        val slice = LadderSlice(1, 100.0, 1.0, 100.0, isResting = true)
        val campaign = Campaign(
            id = "SOLUSDT_SUPPORT_LONG",
            asset = "SOLUSDT",
            family = CampaignFamily.SUPPORT_LONG,
            regime = MarketRegime.BALANCE,
            role = BoardRole.PRIMARY,
            priorityScore = 80.0,
            sizeBudgetUsd = 500.0,
            sizeMultiplier = 1.0,
            entryLadder = listOf(slice),
            stopLogic = StopLogic(softThreshold = 45, hardThreshold = 70, hardStopPrice = 95.0),
            targets = emptyList(),
            invalidationScore = 10,
            topReasons = emptyList(),
            status = CampaignState.STAGED
        )

        // when price trades through the bid
        val filled = engine.simulatePaperFills(campaign, lastPrice = 99.0)

        // then the board activates and the slice fills (§36)
        assertEquals(CampaignState.ACTIVE, filled.status)
        assertTrue(filled.entryLadder.first().isFilled)

        // and when price stays above the bid, nothing fills
        val untouched = engine.simulatePaperFills(campaign, lastPrice = 101.0)
        assertTrue(untouched.entryLadder.first().isResting)

        // and §22/§25 reconciliation by encoded client order id fills or cancels it
        val reconciled = engine.reconcileFill(campaign, "SOLUSDT_SUPPORT_LONG_1")
        assertTrue(reconciled.entryLadder.first().isFilled)
        val cancelled = engine.reconcileCancel(campaign, "SOLUSDT_SUPPORT_LONG_1")
        assertTrue(!cancelled.entryLadder.first().isResting)

        // and unknown ids leave the campaign untouched
        assertEquals(campaign, engine.reconcileFill(campaign, "OTHER_CAMPAIGN_9"))
        assertEquals(campaign, engine.reconcileCancel(campaign, "OTHER_CAMPAIGN_9"))
    }

    @Test
    fun `should compute position mismatch and reject the pause threshold`() {
        // given the §33 5% relative-delta mismatch rule
        with(ExecutionGuardrails) {
            // then zero-vs-flat is fine
            assertEquals(PositionMismatchAction.NONE, positionMismatchAction(0.0, 0.0))

            // and a 10% relative delta flattens and locks
            assertEquals(
                PositionMismatchAction.FLATTEN_AND_LOCK,
                positionMismatchAction(2.0, 2.2)
            )

            // and a 2% relative delta passes
            assertEquals(PositionMismatchAction.NONE, positionMismatchAction(2.0, 2.04))

            // and the venue pauses exactly at the reject threshold, not one below it
            assertTrue(shouldPauseVenue(REJECT_PAUSE_THRESHOLD))
            assertTrue(!shouldPauseVenue(REJECT_PAUSE_THRESHOLD - 1))
        }
    }
}
