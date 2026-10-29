package com.example

import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.RiskEnvelope
import com.example.data.model.VenueHealth
import com.example.engine.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §20 exposure safety, §33 venue/health gating and §24 override guardrails.
 *
 * Scenario: the compliance review found `validateCampaignStaging` was never called, so
 * kill switch, daily stop, exposure caps and side conflicts were decorative. These
 * scenarios pin the validator's decisions and the operator-facing reasons.
 */
class RiskEngineTest {

    private val engine = RiskEngine()
    private val healthyVenue = VenueHealth()

    private fun validate(
        candidate: Campaign = Fixtures.campaign(),
        existing: List<Campaign> = emptyList(),
        envelope: RiskEnvelope = RiskEnvelope(),
        venueHealth: VenueHealth = healthyVenue
    ): Pair<Boolean, String?> = engine.validateCampaignStaging(candidate, existing, envelope, venueHealth)

    @Test
    fun `should allow a board that sits inside every limit`() {
        // given a $300 board, no conflicts and a healthy venue
        // when staging is validated
        val (allowed, reason) = validate()

        // then it is approved with no reason code
        assertTrue(allowed)
        assertNull(reason)
    }

    @Test
    fun `should lock staging while the kill switch is engaged`() {
        // given an engaged §38 kill switch
        val (allowed, reason) = validate(envelope = RiskEnvelope(isKillSwitchEngaged = true))

        // then no board may be placed
        assertFalse(allowed)
        assertTrue(reason!!.contains("Kill-Switch"))
    }

    @Test
    fun `should pause staging once the daily loss limit is reached`() {
        // given a 2R day against a 2R daily stop
        val (allowed, reason) = validate(envelope = RiskEnvelope(currentRealizedLossR = 2.0))

        // then automation pauses with the limit quoted back
        assertFalse(allowed)
        assertTrue(reason!!.contains("Daily loss limit"))
    }

    @Test
    fun `should refuse staging on an unhealthy venue`() {
        // given a thermally throttled device
        val (allowed, reason) = validate(venueHealth = VenueHealth(isThermallyThrottled = true))

        // then the board is refused before routing
        assertFalse(allowed)
        assertTrue(reason!!.contains("health guardrail"))
    }

    @Test
    fun `should refuse a breakdown short opposite an active support long`() {
        // given an active full-size support long on the same symbol
        val existing = listOf(
            Fixtures.campaign(id = "SUPPORT", family = CampaignFamily.SUPPORT_LONG, status = CampaignState.ACTIVE)
        )

        // when a breakdown short is staged on that symbol
        val (allowed, reason) = validate(
            candidate = Fixtures.campaign(id = "BREAKDOWN", family = CampaignFamily.BREAKDOWN_SHORT),
            existing = existing
        )

        // then the opposing full-size boards are refused (§20)
        assertFalse(allowed)
        assertTrue(reason!!.contains("Conflict"))

        // and the rule stays narrow: a merely staged support long does not block it
        val narrowed = validate(
            candidate = Fixtures.campaign(id = "BREAKDOWN", family = CampaignFamily.BREAKDOWN_SHORT),
            existing = listOf(Fixtures.campaign(id = "SUPPORT", status = CampaignState.STAGED))
        )
        assertTrue(narrowed.first)
    }

    @Test
    fun `should refuse a board that breaches the gross exposure cap`() {
        // given $99,900 of open exposure against a $100,000 cap
        val envelope = RiskEnvelope(currentGrossExposureUsd = 99_900.0, maxGrossExposureUsd = 100_000.0)

        // when a $300 board is validated
        val (allowed, reason) = validate(envelope = envelope)

        // then the cap refuses it
        assertFalse(allowed)
        assertTrue(reason!!.contains("Gross exposure cap"))
    }

    @Test
    fun `should warn when an override stretches the structural stop`() {
        // given an engine stop at 100 on both sides
        val stretchedLong = engine.checkManualOverrideGuardrails(
            engineCalculatedStop = 100.0,
            userStopPrice = 96.0,
            isLong = true,
            engineCalculatedSize = 500.0,
            userSizeBudget = 500.0
        )
        val stretchedShort = engine.checkManualOverrideGuardrails(
            engineCalculatedStop = 100.0,
            userStopPrice = 104.0,
            isLong = false,
            engineCalculatedSize = 500.0,
            userSizeBudget = 500.0
        )
        val withinBand = engine.checkManualOverrideGuardrails(
            engineCalculatedStop = 100.0,
            userStopPrice = 98.0,
            isLong = true,
            engineCalculatedSize = 500.0,
            userSizeBudget = 500.0
        )

        // then only the >3% stretches raise the structural warning (§24)
        assertEquals(1, stretchedLong.size)
        assertEquals("Over-Extended Structural Stop Risk", stretchedLong.single().title)
        assertEquals(1, stretchedShort.size)
        assertTrue(withinBand.isEmpty())
    }

    @Test
    fun `should warn when an override exceeds 150 percent of the approved size`() {
        // given an approved $500 budget
        val overSized = engine.checkManualOverrideGuardrails(100.0, 100.0, true, 500.0, 760.0)
        val atLimit = engine.checkManualOverrideGuardrails(100.0, 100.0, true, 500.0, 750.0)

        // when the override is compared, then only the >150% allocation warns
        assertEquals(1, overSized.size)
        assertEquals("Excessive Position Sizing Allocation", overSized.single().title)
        assertTrue(atLimit.isEmpty())
    }

    @Test
    fun `should compute worst-case concurrent stop-out in R`() {
        // given $4,000 of staged/active notional on a $10,000 account (1R = $50)
        val campaigns = listOf(
            Fixtures.campaign(id = "STAGED", sizeBudgetUsd = 2000.0, prices = listOf(100.0)),
            Fixtures.campaign(
                id = "ACTIVE",
                status = CampaignState.ACTIVE,
                sizeBudgetUsd = 2000.0,
                prices = listOf(100.0)
            )
        )

        // when the worst case is computed
        val worstCaseR = engine.computeWorstCaseStopOutR(campaigns, accountEquity = 10_000.0)

        // then it is $4,000 x 2.5% = $100 over $50 per R = 2.0R
        assertEquals(2.0, worstCaseR, 1e-9)
    }

    @Test
    fun `should exclude planned boards from the worst-case stop-out`() {
        // given only a planned board
        val campaigns = listOf(
            Fixtures.campaign(status = CampaignState.PLANNED, sizeBudgetUsd = 2000.0, prices = listOf(100.0))
        )

        // when the worst case is computed
        val worstCaseR = engine.computeWorstCaseStopOutR(campaigns, accountEquity = 10_000.0)

        // then nothing is at risk yet
        assertEquals(0.0, worstCaseR, 1e-9)
    }
}
