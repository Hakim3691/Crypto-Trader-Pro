package com.example

import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.LadderSlice
import com.example.data.model.MarketRegime
import com.example.data.model.StopLogic
import com.example.engine.HardwareDiagnosticsProvider
import com.example.engine.OrphanInterceptDecision
import com.example.engine.OrphanInterceptPolicy
import com.example.engine.SessionSecurityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: lifecycle-driven orphan interception (Spec §2, §3).
 *
 * Scenario: on app launch the previous session's credentials are still in RAM and orders
 * may still rest on the venue. Interception must depend on BOTH a live session and
 * actually resting/live orders, so a clean shutdown never nags the user.
 */
class OrphanInterceptPolicyTest {

    private fun campaign(
        status: CampaignState,
        restingSlices: Int = 0,
        filledSlices: Int = 0
    ): Campaign = Campaign(
        id = "T_${status.name}",
        asset = "SOLUSDT",
        family = CampaignFamily.SUPPORT_LONG,
        regime = MarketRegime.BULLISH_CONTINUATION,
        role = BoardRole.PRIMARY,
        priorityScore = 80.0,
        sizeBudgetUsd = 500.0,
        sizeMultiplier = 1.0,
        entryLadder = List(restingSlices) { i ->
            LadderSlice(sliceIndex = i + 1, price = 150.0, qty = 1.0, notionalUsd = 150.0, isFilled = false, isResting = true)
        } + List(filledSlices) { i ->
            LadderSlice(
                sliceIndex = restingSlices + i + 1,
                price = 150.0,
                qty = 1.0,
                notionalUsd = 150.0,
                isFilled = true,
                isResting = false
            )
        },
        stopLogic = StopLogic(softThreshold = 45, hardThreshold = 70, hardStopPrice = 146.0),
        targets = emptyList(),
        invalidationScore = 10,
        topReasons = emptyList(),
        status = status
    )

    @Test
    fun `should treat every live campaign state as resting or live orders`() {
        // given one campaign in each non-terminal lifecycle state
        val liveStates = listOf(
            CampaignState.STAGED,
            CampaignState.PARTIALLY_FILLED,
            CampaignState.ACTIVE,
            CampaignState.REDUCED
        )

        // when each state alone is inspected
        for (state in liveStates) {
            // then it counts as live, even with an empty ladder
            assertTrue(
                "Expected $state to count as live",
                OrphanInterceptPolicy.hasRestingOrLiveOrders(listOf(campaign(state)))
            )
        }
    }

    @Test
    fun `should treat a completed campaign with a resting slice as live orders`() {
        // given a COMPLETED campaign that still has one slice working
        val completedWithRest = campaign(CampaignState.COMPLETED, restingSlices = 1)

        // when the board is inspected
        val hasLiveOrders = OrphanInterceptPolicy.hasRestingOrLiveOrders(listOf(completedWithRest))

        // then the terminal status does not hide the live slice
        assertTrue(hasLiveOrders)
    }

    @Test
    fun `should not treat a terminal campaign with nothing resting as live orders`() {
        // given a CANCELLED campaign whose only slice was already filled
        val cancelledFilled = campaign(CampaignState.CANCELLED, filledSlices = 1)

        // when the board is inspected
        val hasLiveOrders = OrphanInterceptPolicy.hasRestingOrLiveOrders(listOf(cancelledFilled))

        // then there is nothing to intercept
        assertFalse(hasLiveOrders)
    }

    @Test
    fun `should never intercept when no session is active`() {
        // given orders resting but no live session
        val hasActiveSession = false
        val hasRestingOrders = true

        // when the intercept decision is made
        val decision = OrphanInterceptPolicy.decide(hasActiveSession, hasRestingOrders)

        // then no action is taken
        assertEquals(OrphanInterceptDecision.NO_ACTION, decision)
    }

    @Test
    fun `should show the intercept when a session is active and orders are resting`() {
        // given a live session with resting orders
        val hasActiveSession = true
        val hasRestingOrders = true

        // when the intercept decision is made
        val decision = OrphanInterceptPolicy.decide(hasActiveSession, hasRestingOrders)

        // then the user is asked to reconcile the orphaned orders
        assertEquals(OrphanInterceptDecision.SHOW_INTERCEPT, decision)
    }

    @Test
    fun `should wipe keys without an intercept when a session has no resting orders`() {
        // given a live session with nothing resting on the venue
        val hasActiveSession = true
        val hasRestingOrders = false

        // when the intercept decision is made
        val decision = OrphanInterceptPolicy.decide(hasActiveSession, hasRestingOrders)

        // then the keys are wiped silently
        assertEquals(OrphanInterceptDecision.WIPE_ONLY, decision)
    }
}

/**
 * Feature: suspension wipe hardening and feed-health propagation (Spec §2, §3).
 *
 * Scenario: credentials live in RAM only; any suspension, restart or blank input must
 * leave no usable session behind, and unreachable mandatory feeds must mark venues
 * unhealthy so guardrails can refuse to stage.
 */
class SessionSecurityHardeningTest {

    @Test
    fun `should deactivate the session on suspension wipe and stay safe when wiped twice`() {
        // given an active session with valid credentials
        val manager = SessionSecurityManager()
        manager.startSession("KEY-123456", "SECRET-987654321")
        assertTrue(manager.hasValidActiveSession())

        // when the suspension wipe runs
        manager.terminateSessionAndWipeRam()

        // then the session is gone
        assertFalse(manager.hasValidActiveSession())

        // when the wipe runs a second time
        manager.terminateSessionAndWipeRam()

        // then it is idempotent: no throw, no resurrected state
        assertFalse(manager.hasValidActiveSession())
    }

    @Test
    fun `should replace prior credentials when a new session starts`() {
        // given a session created with first credentials
        val manager = SessionSecurityManager()
        manager.startSession("FIRST_KEY", "FIRST_SECRET")

        // when a second session replaces it
        manager.startSession("SECOND_KEY", "SECOND_SECRET")

        // then exactly one valid session remains, and wiping clears it
        assertTrue(manager.hasValidActiveSession())
        manager.terminateSessionAndWipeRam()
        assertFalse(manager.hasValidActiveSession())
    }

    @Test
    fun `should mark the mandatory feed stale when the latency probe is unreachable`() {
        // given a hardware sample whose Binance probe timed out
        val manager = SessionSecurityManager()

        // when diagnostics are published
        manager.updateHardwareDiagnostics(
            pingBinance = HardwareDiagnosticsProvider.UNREACHABLE_LATENCY_MS,
            pingCoinGlass = 42L,
            isLowPower = false,
            isThrottled = false,
            availableRamMb = 4000
        )

        // then the feed is stale and the venue is not healthy
        val health = manager.venueHealth.value
        assertTrue(health.isFeedStale)
        assertFalse(health.isHealthy)
    }

    @Test
    fun `should keep the feed fresh but flag a latency spike above the threshold`() {
        // given a reachable Binance probe that exceeds the 120ms spike threshold
        val manager = SessionSecurityManager()

        // when diagnostics are published
        manager.updateHardwareDiagnostics(
            pingBinance = 300L,
            pingCoinGlass = 42L,
            isLowPower = false,
            isThrottled = false,
            availableRamMb = 4000
        )

        // then the feed is fresh yet the venue is unhealthy
        val health = manager.venueHealth.value
        assertFalse(health.isFeedStale)
        assertTrue(health.isLatencySpike)
        assertFalse(health.isHealthy)
    }

    @Test
    fun `should report a healthy venue for a healthy sample`() {
        // given low-latency probes and no hardware pressure
        val manager = SessionSecurityManager()

        // when diagnostics are published
        manager.updateHardwareDiagnostics(
            pingBinance = 30L,
            pingCoinGlass = 40L,
            isLowPower = false,
            isThrottled = false,
            availableRamMb = 4000
        )

        // then the venue is healthy
        assertTrue(manager.venueHealth.value.isHealthy)
    }

    @Test
    fun `should refuse to start a session with blank credentials`() {
        // given blank or whitespace-only key/secret pairs
        val manager = SessionSecurityManager()

        // when both are submitted as sessions
        manager.startSession("  ", "SECRET")
        manager.startSession("KEY", "")

        // then no session is created
        assertFalse(manager.hasValidActiveSession())
    }
}
