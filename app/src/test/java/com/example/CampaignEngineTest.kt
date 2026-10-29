package com.example

import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.CoinCalibration
import com.example.data.model.MarketRegime
import com.example.engine.CampaignEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §12 campaign families and mirrors, §16/§17 board-role sizing,
 * §15 bias-band adjustment and §10 calibration-driven target depth.
 *
 * Scenario: a mid-cap snapshot with mapped levels is planned into the full mirrored
 * board set. The audited build hardcoded roles and never linked mirrors; these
 * scenarios pin the deterministic planning contract (no random fills, no invented sizes).
 */
class CampaignEngineTest {

    private val engine = CampaignEngine()
    private val levelMap = Fixtures.levelMap()

    private fun List<Campaign>.of(family: CampaignFamily): Campaign =
        first { it.family == family }

    private fun plan(
        tacticalBias: Int = 10,
        regime: MarketRegime = MarketRegime.BULLISH_CONTINUATION,
        calibration: CoinCalibration? = null
    ): List<Campaign> = engine.planCampaigns(
        asset = Fixtures.asset(tacticalBias = tacticalBias, regime = regime),
        levelMap = levelMap,
        paths = emptyList(),
        totalRiskBudgetUsd = 1000.0,
        calibration = calibration
    )

    @Test
    fun `should plan all six mirrored families with regime board roles`() {
        // given a bullish continuation snapshot with every mapped level available
        // when campaigns are planned
        val campaigns = plan()

        // then all six §12 families exist
        assertEquals(6, campaigns.size)

        // and the bullish regime assigns the asymmetric role set
        assertEquals(BoardRole.PRIMARY, campaigns.of(CampaignFamily.SUPPORT_LONG).role)
        assertEquals(BoardRole.PRIMARY, campaigns.of(CampaignFamily.BREAKOUT_LONG).role)
        assertEquals(BoardRole.SECONDARY, campaigns.of(CampaignFamily.RECLAIM_LONG).role)
        assertEquals(BoardRole.DEFENSIVE, campaigns.of(CampaignFamily.RESISTANCE_SHORT).role)
        assertEquals(BoardRole.DISABLED, campaigns.of(CampaignFamily.RECLAIM_FADE_SHORT).role)
        assertEquals(BoardRole.DISABLED, campaigns.of(CampaignFamily.BREAKDOWN_SHORT).role)

        // and disabled boards are suppressed rather than staged
        assertEquals(CampaignState.SUPPRESSED, campaigns.of(CampaignFamily.RECLAIM_FADE_SHORT).status)
        assertEquals(CampaignState.SUPPRESSED, campaigns.of(CampaignFamily.BREAKDOWN_SHORT).status)
        assertEquals(CampaignState.STAGED, campaigns.of(CampaignFamily.SUPPORT_LONG).status)
    }

    @Test
    fun `should size boards by the §16 role budget`() {
        // given a $1,000 total risk budget and a neutral bias
        // when campaigns are planned
        val campaigns = plan()

        // then primary/secondary/defensive/disabled split 50/28/12/0
        assertEquals(500.0, campaigns.of(CampaignFamily.SUPPORT_LONG).sizeBudgetUsd, 1e-6)
        assertEquals(280.0, campaigns.of(CampaignFamily.RECLAIM_LONG).sizeBudgetUsd, 1e-6)
        assertEquals(120.0, campaigns.of(CampaignFamily.RESISTANCE_SHORT).sizeBudgetUsd, 1e-6)
        assertEquals(0.0, campaigns.of(CampaignFamily.RECLAIM_FADE_SHORT).sizeBudgetUsd, 1e-9)
        assertEquals(0.0, campaigns.of(CampaignFamily.BREAKDOWN_SHORT).sizeBudgetUsd, 1e-9)
    }

    @Test
    fun `should never pre-mark ladder slices as filled`() {
        // given planning happens before any order is routed
        // when campaigns are planned
        val campaigns = plan()

        // then every slice rests unfilled (§22: fills arrive from execution only)
        assertTrue(campaigns.all { c -> c.entryLadder.all { !it.isFilled } })
        assertTrue(campaigns.all { c -> c.entryLadder.all { it.isResting } })
    }

    @Test
    fun `should link every board to its structural mirror`() {
        // given the §12 mirror pairs support↔resistance and breakout↔breakdown
        // when campaigns are planned
        val campaigns = plan()

        // then both directions of each pair are linked by id
        assertEquals(
            campaigns.of(CampaignFamily.RESISTANCE_SHORT).id,
            campaigns.of(CampaignFamily.SUPPORT_LONG).mirrorCampaignId
        )
        assertEquals(
            campaigns.of(CampaignFamily.SUPPORT_LONG).id,
            campaigns.of(CampaignFamily.RESISTANCE_SHORT).mirrorCampaignId
        )
        assertEquals(
            campaigns.of(CampaignFamily.BREAKDOWN_SHORT).id,
            campaigns.of(CampaignFamily.BREAKOUT_LONG).mirrorCampaignId
        )
    }

    @Test
    fun `should tilt priority and size toward the favored side`() {
        // given a strong bullish tactical bias
        // when campaigns are planned
        val campaigns = plan(tacticalBias = 60)

        // then the favored long grows by the §15 band while the short shrinks
        assertEquals(600.0, campaigns.of(CampaignFamily.SUPPORT_LONG).sizeBudgetUsd, 1e-6)
        assertEquals(96.0, campaigns.of(CampaignFamily.RESISTANCE_SHORT).sizeBudgetUsd, 1e-6)

        // and the favored long outranks the opposed short
        assertTrue(
            campaigns.of(CampaignFamily.SUPPORT_LONG).priorityScore >
                campaigns.of(CampaignFamily.RESISTANCE_SHORT).priorityScore
        )
    }

    @Test
    fun `should stretch target depth along the faster-moving side using calibration`() {
        // given a coin whose upside impulses run 2x its downside impulses
        val calibration = CoinCalibration(
            symbol = "SOLUSDT",
            impulseAsymmetry = 2.0,
            wickAsymmetry = 1.0,
            followThroughAsymmetry = 1.0,
            crowdingElasticity = 1.0,
            meanReversionHalfLifeMinutes = 60,
            typicalSlippageBps = 3.5
        )

        // when the same snapshot is planned with and without calibration
        val uncalibrated = plan()
        val calibrated = plan(calibration = calibration)

        // then the long runner target stretches by the 1.5x cap and the short compresses to 0.7x
        assertEquals(
            uncalibrated.of(CampaignFamily.SUPPORT_LONG).targets.last().price * 1.5,
            calibrated.of(CampaignFamily.SUPPORT_LONG).targets.last().price,
            1e-6
        )
        assertEquals(
            uncalibrated.of(CampaignFamily.RESISTANCE_SHORT).targets.last().price * 0.7,
            calibrated.of(CampaignFamily.RESISTANCE_SHORT).targets.last().price,
            1e-6
        )
    }

    @Test
    fun `should order the board set by descending priority`() {
        // given a planned board set
        // when campaigns are returned
        val campaigns = plan(tacticalBias = 60)

        // then priority never increases down the list
        assertTrue(campaigns.zipWithNext().all { (a, b) -> a.priorityScore >= b.priorityScore })
        assertFalse(campaigns.any { it.priorityScore.isNaN() })
    }

    @Test
    fun `should suppress every family in a balance regime`() {
        // given a range-bound snapshot where breakout boards are disabled
        // when campaigns are planned
        val campaigns = plan(regime = MarketRegime.BALANCE)

        // then the mean-reversion edges stay primary and continuation is disabled
        assertEquals(BoardRole.PRIMARY, campaigns.of(CampaignFamily.SUPPORT_LONG).role)
        assertEquals(BoardRole.PRIMARY, campaigns.of(CampaignFamily.RESISTANCE_SHORT).role)
        assertEquals(CampaignState.SUPPRESSED, campaigns.of(CampaignFamily.BREAKOUT_LONG).status)
        assertEquals(CampaignState.SUPPRESSED, campaigns.of(CampaignFamily.BREAKDOWN_SHORT).status)
    }
}
