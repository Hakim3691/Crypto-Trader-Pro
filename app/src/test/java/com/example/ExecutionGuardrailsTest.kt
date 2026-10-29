package com.example

import com.example.data.model.BoardRole
import com.example.data.model.CampaignState
import com.example.data.model.VenueHealth
import com.example.engine.ExecutionGuardrails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §33 operational guardrails.
 *
 * Scenario: the execution path must react to degraded market/venue conditions BEFORE
 * orders rest. Each guardrail is a pure decision function so the rule can be verified
 * without a venue, a socket or an Android binding (the compliance review flagged these
 * as "not implemented" in the audited build).
 */
class ExecutionGuardrailsTest {

    @Test
    fun `should suppress staging and trim orders when the mandatory feed is stale`() {
        // given one venue whose mandatory-feed probe failed and one that answered
        val staleVenue = VenueHealth(isFeedStale = true)
        val healthyVenue = VenueHealth()

        // when the guardrail classifies both
        val staleAction = ExecutionGuardrails.staleFeedAction(staleVenue)
        val healthyAction = ExecutionGuardrails.staleFeedAction(healthyVenue)

        // then stale evidence suppresses staging and trims nonessential orders
        assertEquals(ExecutionGuardrails.StaleFeedAction.SUPPRESS_AND_TRIM, staleAction)
        assertEquals(ExecutionGuardrails.StaleFeedAction.NONE, healthyAction)
    }

    @Test
    fun `should cancel only nonessential resting slices when trimming`() {
        // given a primary board and a secondary board with one filled and one resting slice
        val primary = Fixtures.campaign(id = "PRIMARY", role = BoardRole.PRIMARY, prices = listOf(100.0, 99.0))
        val secondary = Fixtures.campaign(
            id = "SECONDARY",
            role = BoardRole.SECONDARY,
            prices = listOf(100.0, 99.0),
            sizeBudgetUsd = 200.0,
            filledIndices = setOf(0)
        )

        // when the stale-feed trim runs
        val trimmed = ExecutionGuardrails.trimNonessentialRestingOrders(listOf(primary, secondary))

        // then the primary keeps resting
        assertTrue(trimmed[0].entryLadder.all { it.isResting })

        // and the secondary stops resting while its fill is preserved
        assertFalse(trimmed[1].entryLadder[1].isResting)
        assertTrue(trimmed[1].entryLadder[0].isFilled)
        assertEquals(100.0, trimmed[1].entryLadder[0].notionalUsd, 1e-9)
    }

    @Test
    fun `should treat a spread beyond the volatility-adjusted limit as a blowout`() {
        // given a 100 bps-ATR asset quoted at 50 bps and one quoted at 5 bps
        val wide = Fixtures.asset(atr5m = 1.0, lastPrice = 100.0, orderBookSpreadPct = 0.5)
        val tight = Fixtures.asset(atr5m = 1.0, lastPrice = 100.0, orderBookSpreadPct = 0.05)

        // when the spread guardrail is evaluated
        val wideIsBlowout = ExecutionGuardrails.isSpreadBlowout(wide)
        val tightIsBlowout = ExecutionGuardrails.isSpreadBlowout(tight)

        // then only the wide quote disables passive staging
        assertTrue(wideIsBlowout)
        assertFalse(tightIsBlowout)
    }

    @Test
    fun `should fail safe when the price feed is empty`() {
        // given an asset with no usable price
        val noPrice = Fixtures.asset(lastPrice = 0.0)

        // when the spread guardrail is evaluated
        val isBlowout = ExecutionGuardrails.isSpreadBlowout(noPrice)

        // then passive staging is refused rather than allowed on missing data
        assertTrue(isBlowout)
    }

    @Test
    fun `should pause the venue only once rejects reach the threshold`() {
        // given reject streaks below, at and above the pause threshold
        val below = ExecutionGuardrails.REJECT_PAUSE_THRESHOLD - 1
        val at = ExecutionGuardrails.REJECT_PAUSE_THRESHOLD
        val above = ExecutionGuardrails.REJECT_PAUSE_THRESHOLD + 1

        // when each streak is evaluated
        // then only a threshold-reaching streak pauses the venue
        assertFalse(ExecutionGuardrails.shouldPauseVenue(below))
        assertTrue(ExecutionGuardrails.shouldPauseVenue(at))
        assertTrue(ExecutionGuardrails.shouldPauseVenue(above))
    }

    @Test
    fun `should flatten and lock only when the position mismatch exceeds tolerance`() {
        // given a 2% drift, a 20% drift and two flat books
        val smallDrift = ExecutionGuardrails.positionMismatchAction(expectedQty = 1.0, exchangeQty = 1.02)
        val largeDrift = ExecutionGuardrails.positionMismatchAction(expectedQty = 1.0, exchangeQty = 1.2)
        val bothFlat = ExecutionGuardrails.positionMismatchAction(expectedQty = 0.0, exchangeQty = 0.0)

        // when each is compared against the tolerance
        // then only the material drift forces a flatten and lock
        assertEquals(ExecutionGuardrails.PositionMismatchAction.NONE, smallDrift)
        assertEquals(ExecutionGuardrails.PositionMismatchAction.FLATTEN_AND_LOCK, largeDrift)
        assertEquals(ExecutionGuardrails.PositionMismatchAction.NONE, bothFlat)
    }

    @Test
    fun `should derive the signed expected position from live boards only`() {
        // given an active long holding $600 and an active short holding $300 at 150
        val long = Fixtures.campaign(
            id = "LONG",
            status = CampaignState.ACTIVE,
            prices = listOf(150.0),
            sizeBudgetUsd = 600.0,
            filledIndices = setOf(0)
        )
        val short = Fixtures.campaign(
            id = "SHORT",
            family = com.example.data.model.CampaignFamily.RESISTANCE_SHORT,
            status = CampaignState.ACTIVE,
            prices = listOf(150.0),
            sizeBudgetUsd = 300.0,
            filledIndices = setOf(0)
        )
        val planned = Fixtures.campaign(
            id = "PLANNED",
            status = CampaignState.PLANNED,
            prices = listOf(150.0),
            sizeBudgetUsd = 900.0
        )

        // when the engine's expected signed quantity is computed
        val expectedQty = ExecutionGuardrails.expectedPositionQty(listOf(long, short, planned), 150.0)

        // then it is 4 long minus 2 short, with planned boards excluded
        assertEquals(2.0, expectedQty, 1e-9)
    }

    @Test
    fun `should report zero expected position for an unusable price`() {
        // given live boards but a zero mark price
        val long = Fixtures.campaign(status = CampaignState.ACTIVE, filledIndices = setOf(0))

        // when the expected position is computed
        val expectedQty = ExecutionGuardrails.expectedPositionQty(listOf(long), 0.0)

        // then reconciliation is skipped rather than dividing by zero
        assertEquals(0.0, expectedQty, 1e-9)
    }
}
