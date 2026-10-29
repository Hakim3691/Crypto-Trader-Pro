package com.example

import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.ExecutionMode
import com.example.data.model.RiskEnvelope
import com.example.data.model.VenueHealth
import com.example.engine.ExecutionEngine
import com.example.engine.OrphanInterceptPolicy
import com.example.engine.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §36 execution-mode gating and §22/§25 order lifecycle.
 *
 * Scenario: identical boards must behave differently per operator mode — Shadow never
 * rests orders, Paper rests internally, Capped Live clips notional, and live modes
 * without credentials hold boards as plans. The audited build treated the mode pill as
 * cosmetic and never called the risk validator; these scenarios lock that wiring in.
 */
class ExecutionEngineTest {

    private val engine = ExecutionEngine(RiskEngine())

    private fun gate(
        campaign: Campaign = Fixtures.campaign(),
        mode: ExecutionMode = ExecutionMode.PAPER,
        envelope: RiskEnvelope = RiskEnvelope(),
        hasActiveSession: Boolean = true,
        venueHealth: VenueHealth = VenueHealth()
    ): ExecutionEngine.GatedCampaign =
        engine.gateStaging(listOf(campaign), mode, envelope, venueHealth, hasActiveSession).single()

    @Test
    fun `should leave no resting slices in shadow mode`() {
        // given a staged board and an operator in shadow mode
        val board = Fixtures.campaign()

        // when staging is gated
        val gated = gate(board, mode = ExecutionMode.SHADOW)

        // then the board stays a plan with nothing resting on the venue
        assertEquals(CampaignState.PLANNED, gated.campaign.status)
        assertTrue(gated.campaign.entryLadder.none { it.isResting })
        assertTrue(gated.note!!.contains("Shadow"))
    }

    @Test
    fun `should rest boards internally in paper mode`() {
        // given a staged board and paper mode
        // when staging is gated
        val gated = gate(mode = ExecutionMode.PAPER)

        // then the board rests against the live book with no exchange call
        assertEquals(CampaignState.STAGED, gated.campaign.status)
        assertTrue(gated.campaign.entryLadder.all { it.isResting })
        assertTrue(gated.note!!.contains("Paper"))
    }

    @Test
    fun `should clip capped-live notional to the per-board cap`() {
        // given a $1,000 board and a $250 capped-live ceiling
        val board = Fixtures.campaign(sizeBudgetUsd = 1000.0)

        // when capped-live staging is gated
        val gated = gate(board, mode = ExecutionMode.CAPPED_LIVE)

        // then the budget and every slice are scaled down to the cap
        assertEquals(250.0, gated.campaign.sizeBudgetUsd, 1e-9)
        assertEquals(250.0, gated.campaign.entryLadder.sumOf { it.notionalUsd }, 0.05)
        assertTrue(gated.note!!.contains("clipped"))
    }

    @Test
    fun `should hold live boards as plans without an active session`() {
        // given no signed session
        // when each live mode tries to stage
        val capped = gate(mode = ExecutionMode.CAPPED_LIVE, hasActiveSession = false)
        val scaled = gate(mode = ExecutionMode.SCALED_LIVE, hasActiveSession = false)

        // then neither routes orders nor silently drops the plan
        assertEquals(CampaignState.PLANNED, capped.campaign.status)
        assertEquals(CampaignState.PLANNED, scaled.campaign.status)
        assertTrue(capped.note!!.contains("requires an active session"))
        assertTrue(scaled.note!!.contains("requires an active session"))
    }

    @Test
    fun `should suppress staging while the kill switch is engaged`() {
        // given an engaged kill switch
        val envelope = RiskEnvelope(isKillSwitchEngaged = true)

        // when any mode tries to stage
        val gated = gate(mode = ExecutionMode.PAPER, envelope = envelope)

        // then the board is suppressed with the operator-facing reason
        assertEquals(CampaignState.SUPPRESSED, gated.campaign.status)
        assertNotNull(gated.note)
        assertTrue(gated.note!!.contains("Kill-Switch"))
    }

    @Test
    fun `should suppress staging on an unhealthy venue`() {
        // given a thermally throttled device
        val throttled = VenueHealth(isThermallyThrottled = true)

        // when a board tries to stage
        val gated = gate(mode = ExecutionMode.PAPER, venueHealth = throttled)

        // then the board never reaches the venue
        assertEquals(CampaignState.SUPPRESSED, gated.campaign.status)
        assertTrue(gated.note!!.contains("health guardrail"))
    }

    @Test
    fun `should fill a passive long bid only when price trades down to it`() {
        // given a long board laddered at 100, 99 and 98
        val board = Fixtures.campaign(prices = listOf(100.0, 99.0, 98.0))

        // when price trades above the ladder
        val above = engine.simulatePaperFills(board, 101.0)

        // then nothing fills
        assertEquals(0, above.entryLadder.count { it.isFilled })
        assertEquals(CampaignState.STAGED, above.status)

        // when price trades through the top slice only
        val partial = engine.simulatePaperFills(board, 99.5)

        // then exactly one slice fills and the board is partially filled
        assertEquals(1, partial.entryLadder.count { it.isFilled })
        assertEquals(CampaignState.PARTIALLY_FILLED, partial.status)

        // when price trades through the whole ladder
        val filled = engine.simulatePaperFills(board, 97.0)

        // then the board is active
        assertEquals(3, filled.entryLadder.count { it.isFilled })
        assertEquals(CampaignState.ACTIVE, filled.status)
    }

    @Test
    fun `should fill a passive short offer only when price trades up to it`() {
        // given a short board laddered at 100, 101 and 102
        val board = Fixtures.campaign(
            family = CampaignFamily.RESISTANCE_SHORT,
            prices = listOf(100.0, 101.0, 102.0)
        )

        // when price trades below the ladder
        val below = engine.simulatePaperFills(board, 99.0)

        // then nothing fills
        assertEquals(0, below.entryLadder.count { it.isFilled })

        // when price trades through the whole ladder
        val filled = engine.simulatePaperFills(board, 102.5)

        // then the short is fully active
        assertEquals(CampaignState.ACTIVE, filled.status)
        assertEquals(3, filled.entryLadder.count { it.isFilled })
    }

    @Test
    fun `should never simulate fills for a terminal board`() {
        // given a cancelled board whose ladder is still marked resting
        val cancelled = Fixtures.campaign(status = CampaignState.CANCELLED)

        // when a paper fill pass runs through the ladder
        val result = engine.simulatePaperFills(cancelled, 90.0)

        // then the board is returned untouched
        assertEquals(cancelled, result)
        assertEquals(0, result.entryLadder.count { it.isFilled })
    }

    @Test
    fun `should reconcile exchange fills and cancels by client order id`() {
        // given a staged board with a deterministic client-order-id scheme
        val board = Fixtures.campaign(id = "SOLUSDT_SUPPORT_LONG")

        // when the exchange reports a fill on slice 2
        val filled = engine.reconcileFill(board, engine.clientOrderId(board.id, 2))

        // then only that slice fills and the board becomes partially filled
        assertTrue(filled.entryLadder[1].isFilled)
        assertEquals(1, filled.entryLadder.count { it.isFilled })
        assertEquals(CampaignState.PARTIALLY_FILLED, filled.status)

        // when the exchange cancels slice 3
        val cancelled = engine.reconcileCancel(filled, engine.clientOrderId(board.id, 3))

        // then slice 3 stops resting and the fill is untouched
        assertFalse(cancelled.entryLadder[2].isResting)
        assertTrue(cancelled.entryLadder[1].isFilled)
    }

    @Test
    fun `should ignore fill events that belong to another campaign`() {
        // given a staged board
        val board = Fixtures.campaign(id = "SOLUSDT_SUPPORT_LONG")

        // when a foreign client order id arrives
        val result = engine.reconcileFill(board, "ETHUSDT_RESISTANCE_SHORT_1")

        // then the board is unchanged
        assertEquals(board, result)
        assertEquals(0, result.entryLadder.count { it.isFilled })
    }

    @Test
    fun `should cancel a board once the exchange cancels its last resting slice`() {
        // given a staged board with three resting slices
        val board = Fixtures.campaign(id = "SOLUSDT_SUPPORT_LONG")

        // when one slice is cancelled the board still has working orders
        val partial = engine.reconcileCancel(board, engine.clientOrderId(board.id, 1))
        assertEquals(CampaignState.STAGED, partial.status)
        assertTrue(partial.entryLadder.drop(1).all { it.isResting })

        // when the exchange confirms cancellation of every remaining slice
        var cancelled = partial
        for (i in 2..3) cancelled = engine.reconcileCancel(cancelled, engine.clientOrderId(board.id, i))

        // then the board is terminal rather than a phantom live board (Spec §19)
        assertEquals(CampaignState.CANCELLED, cancelled.status)
        assertTrue(cancelled.entryLadder.none { it.isResting })

        // and it no longer counts as resting orders for the §3 orphan intercept
        assertFalse(OrphanInterceptPolicy.hasRestingOrLiveOrders(listOf(cancelled)))
    }
}
