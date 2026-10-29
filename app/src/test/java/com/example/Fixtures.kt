package com.example

import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.CryptoAsset
import com.example.data.model.EvidenceFrame
import com.example.data.model.LadderSlice
import com.example.data.model.LevelMap
import com.example.data.model.LevelZone
import com.example.data.model.LiquidationContext
import com.example.data.model.MarketRegime
import com.example.data.model.OrderType
import com.example.data.model.StopLogic
import com.example.data.model.ZoneType
import com.example.data.remote.BinanceFuturesClient

/**
 * Shared deterministic fixtures for the Spec-compliance BDD suites.
 *
 * Every builder defaults to a neutral, valid SOLUSDT snapshot so each scenario only
 * states the inputs it actually depends on (the "given" it cares about).
 */
object Fixtures {

    fun asset(
        symbol: String = "SOLUSDT",
        lastPrice: Double = 100.0,
        priceChange24h: Double = 0.0,
        quoteVolume24h: Double = 100_000_000.0,
        orderBookSpreadPct: Double = 0.02,
        fundingRatePct: Double = 0.0,
        openInterestUsd: Double = 50_000_000.0,
        basisPremiumPct: Double = 0.0,
        atr5m: Double = 1.0,
        regime: MarketRegime = MarketRegime.BULLISH_CONTINUATION,
        tacticalBias: Int = 10,
        strategicBias: Int = 10,
        liquidationContext: LiquidationContext = LiquidationContext.EMPTY
    ): CryptoAsset = CryptoAsset(
        symbol = symbol,
        baseAsset = symbol.removeSuffix("USDT"),
        lastPrice = lastPrice,
        priceChange24h = priceChange24h,
        quoteVolume24h = quoteVolume24h,
        orderBookSpreadPct = orderBookSpreadPct,
        fundingRatePct = fundingRatePct,
        predictedFundingPct = fundingRatePct,
        openInterestUsd = openInterestUsd,
        basisPremiumPct = basisPremiumPct,
        atr5m = atr5m,
        atr1h = atr5m * 3.0,
        atr4h = atr5m * 8.0,
        regime = regime,
        tacticalBias = tacticalBias,
        strategicBias = strategicBias,
        primaryPainPath = "Fixture pain path"
    ).copy(liquidationContext = liquidationContext)

    fun slice(
        index: Int,
        price: Double,
        notional: Double,
        filled: Boolean = false,
        resting: Boolean = true,
        orderType: OrderType = OrderType.PASSIVE_LIMIT
    ): LadderSlice = LadderSlice(
        sliceIndex = index,
        price = price,
        qty = if (price > 0.0) notional / price else 0.0,
        notionalUsd = notional,
        orderType = orderType,
        isFilled = filled,
        isResting = resting
    )

    fun campaign(
        id: String = "SOLUSDT_SUPPORT_LONG",
        asset: String = "SOLUSDT",
        family: CampaignFamily = CampaignFamily.SUPPORT_LONG,
        role: BoardRole = BoardRole.PRIMARY,
        status: CampaignState = CampaignState.STAGED,
        prices: List<Double> = listOf(100.0, 99.0, 98.0),
        sizeBudgetUsd: Double = 300.0,
        filledIndices: Set<Int> = emptySet(),
        restingIndices: Set<Int>? = null,
        softThreshold: Int = 45,
        hardThreshold: Int = 70,
        invalidationScore: Int = 10,
        createdAt: Long = System.currentTimeMillis(),
        cooldownUntilTs: Long = 0L
    ): Campaign {
        val ladder = prices.mapIndexed { i, price ->
            val notional = sizeBudgetUsd / prices.size
            slice(
                index = i + 1,
                price = price,
                notional = notional,
                filled = i in filledIndices,
                resting = i !in filledIndices && (restingIndices == null || i in restingIndices)
            )
        }
        return Campaign(
            id = id,
            asset = asset,
            family = family,
            regime = MarketRegime.BULLISH_CONTINUATION,
            role = role,
            priorityScore = 80.0,
            sizeBudgetUsd = sizeBudgetUsd,
            sizeMultiplier = 1.0,
            entryLadder = ladder,
            stopLogic = StopLogic(
                softThreshold = softThreshold,
                hardThreshold = hardThreshold,
                hardStopPrice = 96.0
            ),
            targets = emptyList(),
            invalidationScore = invalidationScore,
            topReasons = emptyList(),
            status = status,
            cooldownUntilTs = cooldownUntilTs,
            createdAt = createdAt
        )
    }

    fun zone(
        type: ZoneType,
        low: Double,
        high: Double,
        timeframe: String = "4h",
        freshness: Int = 90
    ): LevelZone = LevelZone(
        id = "Z_${type.name}_${low}_$high",
        zoneType = type,
        priceLow = low,
        priceHigh = high,
        timeframe = timeframe,
        confidence = 85,
        touches = 3,
        freshnessScore = freshness
    )

    fun levelMap(
        zones: List<LevelZone> = listOf(
            zone(ZoneType.HTF_SUPPORT, 95.0, 98.0),
            zone(ZoneType.HTF_RESISTANCE, 104.0, 107.0),
            zone(ZoneType.RECLAIM_ZONE, 101.0, 102.0),
            zone(ZoneType.LTF_PIVOT, 99.0, 100.0, timeframe = "1h"),
            zone(ZoneType.BREAKDOWN_ZONE, 92.0, 94.0)
        ),
        dominantPivot: Double = 100.0
    ): LevelMap = LevelMap(
        asset = "SOLUSDT",
        zones = zones,
        dominantPivot = dominantPivot,
        damageScore = 20,
        confidenceSummary = 85,
        timestamp = 0L
    )

    fun evidence(
        asset: String = "SOLUSDT",
        oiDelta1hPct: Double = 0.0,
        fundingRateChangePct: Double = 0.0,
        basisDeltaBps: Double = 0.0,
        takerBuyRatio: Double = 0.5,
        trapProbabilityPct: Int = 42,
        liquidationContext: LiquidationContext = LiquidationContext.EMPTY
    ): EvidenceFrame = EvidenceFrame(
        timestamp = 0L,
        asset = asset,
        oiDelta1hPct = oiDelta1hPct,
        fundingRateChangePct = fundingRateChangePct,
        basisDeltaBps = basisDeltaBps,
        takerBuyRatio = takerBuyRatio,
        acceptanceQuality = 0.5,
        rejectionQuality = 0.5,
        trapProbabilityPct = trapProbabilityPct,
        liquidationContext = liquidationContext,
        recentImpulseQuality = "Balanced Tape"
    )

    fun candle(
        index: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 1_000.0
    ): BinanceFuturesClient.Candle = BinanceFuturesClient.Candle(
        openTimeUtcMs = index * 300_000L,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        closeTimeUtcMs = index * 300_000L + 299_999L,
        quoteVolume = volume * close
    )
}
