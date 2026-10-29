package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.CryptoAsset
import com.example.data.model.EvidenceFrame
import com.example.data.model.LadderSlice
import com.example.data.model.MarketRegime
import com.example.data.model.StopLogic
import com.example.engine.CampaignEngine
import com.example.engine.FeatureEngine
import com.example.engine.InvalidationAction
import com.example.engine.InvalidationEngine
import com.example.engine.PathEngine
import com.example.engine.RiskEngine
import com.example.engine.SessionSecurityManager
import com.example.engine.StructureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature: deterministic analysis engines under a Robolectric runtime.
 *
 * Scenarios cover the level map, campaign planning, evidence-driven invalidation, the
 * manual-override guardrails and the RAM-only credential wipe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private fun asset(
        lastPrice: Double,
        priceChange24h: Double,
        orderBookSpreadPct: Double,
        fundingRatePct: Double,
        regime: MarketRegime,
        tacticalBias: Int,
        strategicBias: Int,
        primaryPainPath: String,
        quoteVolume24h: Double = 300_000_000.0,
        openInterestUsd: Double = 150_000_000.0,
        predictedFundingPct: Double = -0.02,
        basisPremiumPct: Double = 0.05
    ) = CryptoAsset(
        symbol = "SOLUSDT",
        baseAsset = "SOL",
        lastPrice = lastPrice,
        priceChange24h = priceChange24h,
        quoteVolume24h = quoteVolume24h,
        orderBookSpreadPct = orderBookSpreadPct,
        fundingRatePct = fundingRatePct,
        predictedFundingPct = predictedFundingPct,
        openInterestUsd = openInterestUsd,
        basisPremiumPct = basisPremiumPct,
        atr5m = 1.0,
        atr1h = 2.5,
        atr4h = 6.0,
        regime = regime,
        tacticalBias = tacticalBias,
        strategicBias = strategicBias,
        primaryPainPath = primaryPainPath
    )

    @Test
    fun `should read the app name from context`() {
        // given an application context
        val context = ApplicationProvider.getApplicationContext<Context>()

        // when the app name string is resolved
        val appName = context.getString(R.string.app_name)

        // then it is the product name
        assertEquals("ProactiveMS", appName)
    }

    @Test
    fun `should compute a level map whose zone freshness decays over time`() {
        // given a bullish asset
        val structureEngine = StructureEngine()
        val testAsset = asset(
            lastPrice = 150.0,
            priceChange24h = 3.5,
            orderBookSpreadPct = 0.02,
            fundingRatePct = -0.015,
            regime = MarketRegime.BULLISH_CONTINUATION,
            tacticalBias = 70,
            strategicBias = 65,
            primaryPainPath = "Ascending Sweep",
            quoteVolume24h = 500_000_000.0,
            openInterestUsd = 200_000_000.0
        )

        // when the level map is built and one zone is aged 60 minutes
        val levelMap = structureEngine.buildLevelMap(testAsset)
        val zone = levelMap.zones.first()
        val decayed = structureEngine.decayZoneFreshness(zone, elapsedMinutes = 60)

        // then a map of at least five zones exists and freshness never increases
        assertNotNull(levelMap)
        assertTrue(levelMap.zones.size >= 5)
        assertTrue(decayed.freshnessScore <= zone.freshnessScore)
    }

    @Test
    fun `should plan all six campaign families with asymmetric roles in a bullish regime`() {
        // given a strongly bullish asset
        val structureEngine = StructureEngine()
        val featureEngine = FeatureEngine()
        val pathEngine = PathEngine(featureEngine)
        val campaignEngine = CampaignEngine()

        val bullAsset = asset(
            lastPrice = 150.0,
            priceChange24h = 4.0,
            orderBookSpreadPct = 0.02,
            fundingRatePct = -0.015,
            regime = MarketRegime.BULLISH_CONTINUATION,
            tacticalBias = 75,
            strategicBias = 80,
            primaryPainPath = "Bullish continuation sweep"
        )

        // when paths are ranked and campaigns are planned
        val map = structureEngine.buildLevelMap(bullAsset)
        val paths = pathEngine.rankCandidatePaths(bullAsset, map)
        val campaigns = campaignEngine.planCampaigns(bullAsset, map, paths)

        // then every family is accounted for exactly once
        assertEquals(6, campaigns.size)

        // and the with-trend long is primary while the counter-trend short is disabled
        val supportLong = campaigns.find { it.family == CampaignFamily.SUPPORT_LONG }
        assertNotNull(supportLong)
        assertEquals(BoardRole.PRIMARY, supportLong!!.role)

        val breakdownShort = campaigns.find { it.family == CampaignFamily.BREAKDOWN_SHORT }
        assertNotNull(breakdownShort)
        assertEquals(BoardRole.DISABLED, breakdownShort!!.role)
    }

    @Test
    fun `should cut a long early when adverse evidence contradicts it`() {
        // given an active long whose asset has broken support
        val invalidationEngine = InvalidationEngine()
        val structureEngine = StructureEngine()

        val asset = asset(
            lastPrice = 145.0,          // below the 146 hard stop
            priceChange24h = -3.5,
            orderBookSpreadPct = 0.09,  // spread blown out
            fundingRatePct = 0.03,      // funding flipped positive
            regime = MarketRegime.BEARISH_CONTINUATION,
            tacticalBias = -70,
            strategicBias = -65,
            primaryPainPath = "Breakdown",
            quoteVolume24h = 200_000_000.0,
            openInterestUsd = 120_000_000.0,
            predictedFundingPct = 0.04,
            basisPremiumPct = -0.05
        )
        val map = structureEngine.buildLevelMap(asset)

        val campaign = Campaign(
            id = "TEST_LONG",
            asset = "SOLUSDT",
            family = CampaignFamily.SUPPORT_LONG,
            regime = MarketRegime.BULLISH_CONTINUATION,
            role = BoardRole.PRIMARY,
            priorityScore = 80.0,
            sizeBudgetUsd = 500.0,
            sizeMultiplier = 1.0,
            entryLadder = listOf(
                LadderSlice(1, 150.0, 1.0, 150.0, isFilled = true, isResting = false),
                LadderSlice(2, 149.0, 1.0, 149.0, isFilled = false, isResting = true)
            ),
            stopLogic = StopLogic(softThreshold = 45, hardThreshold = 70, hardStopPrice = 146.0),
            targets = emptyList(),
            invalidationScore = 10,
            topReasons = emptyList(),
            status = CampaignState.ACTIVE
        )

        val adverseEvidence = EvidenceFrame(
            timestamp = System.currentTimeMillis(),
            asset = "SOLUSDT",
            oiDelta1hPct = 2.5,
            fundingRateChangePct = 0.005,
            basisDeltaBps = -10.0,
            takerBuyRatio = 0.15,       // 85% taker sell pressure
            acceptanceQuality = 0.1,
            rejectionQuality = 0.1,
            trapProbabilityPct = 85,
            recentImpulseQuality = "Heavy Taker Sell Pressure"
        )

        // when the campaign is evaluated against that evidence
        val result = invalidationEngine.evaluateCampaign(campaign, asset, adverseEvidence, map)

        // then the score clears the hard threshold and the position is flattened and cooled down
        assertTrue(result.updatedCampaign.invalidationScore >= 70)
        assertEquals(InvalidationAction.HARD_FLATTEN_COOLDOWN, result.triggerAction)
        assertEquals(CampaignState.CANCELLED, result.updatedCampaign.status)
    }

    @Test
    fun `should warn when a manual override breaches stop and sizing guardrails`() {
        // given a stop 10 units below the engine stop and 3x the size budget
        val riskEngine = RiskEngine()

        // when the override is checked
        val warnings = riskEngine.checkManualOverrideGuardrails(
            engineCalculatedStop = 145.0,
            userStopPrice = 135.0,
            isLong = true,
            engineCalculatedSize = 500.0,
            userSizeBudget = 1500.0
        )

        // then both breaches are reported
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.title.contains("Stop Risk") })
        assertTrue(warnings.any { it.title.contains("Position Sizing") })
    }

    @Test
    fun `should wipe api keys from ram on suspension`() {
        // given a live session holding exchange credentials
        val manager = SessionSecurityManager()
        manager.startSession("BINANCE_KEY_12345", "BINANCE_SECRET_ABCDE")
        assertTrue(manager.hasValidActiveSession())

        // when the suspension wipe runs
        manager.terminateSessionAndWipeRam()

        // then no usable session remains
        assertFalse(manager.hasValidActiveSession())
    }
}
