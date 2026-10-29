package com.example

import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.InvalidationFamily
import com.example.data.model.ZoneType
import com.example.engine.InvalidationAction
import com.example.engine.InvalidationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §27 invalidation thresholds, §26 rearm/promotion and §28 mirror activation.
 *
 * Scenario: evidence arrives while a board is resting. Moderate evidence must cancel
 * resting slices, severe evidence must flatten and start a cooldown, a stale hypothesis
 * must expire on time, and a failed board must hand its hypothesis to the mirror family.
 */
class InvalidationEngineTest {

    private val engine = InvalidationEngine()
    private val levelMap = Fixtures.levelMap()

    @Test
    fun `should ignore campaigns that are already terminal`() {
        // given a cancelled board
        val cancelled = Fixtures.campaign(status = CampaignState.CANCELLED)

        // when fresh evidence is evaluated
        val result = engine.evaluateCampaign(cancelled, Fixtures.asset(), Fixtures.evidence(), levelMap)

        // then no score, ladder or status is touched
        assertEquals(InvalidationAction.NO_ACTION, result.triggerAction)
        assertEquals(cancelled, result.updatedCampaign)
    }

    @Test
    fun `should soft-cancel resting slices on a moderate evidence break`() {
        // given a long laddered at 100/99/98 and price accepting 97
        val board = Fixtures.campaign(prices = listOf(100.0, 99.0, 98.0))
        val asset = Fixtures.asset(lastPrice = 97.0, atr5m = 1.0)

        // and no taker buy response at the shelf
        val evidence = Fixtures.evidence(takerBuyRatio = 0.30)

        // when invalidation is evaluated
        val result = engine.evaluateCampaign(board, asset, evidence, levelMap)

        // then structure failure (35) plus flow failure (18) soft-cancels the board
        assertEquals(InvalidationAction.SOFT_CANCEL_RESTING, result.triggerAction)
        assertEquals(53, result.updatedCampaign.invalidationScore)
        assertEquals(CampaignState.CANCELLED, result.updatedCampaign.status)
        assertTrue(result.updatedCampaign.entryLadder.none { it.isResting })
        assertTrue(result.updatedCampaign.topReasons.any { it.family == InvalidationFamily.STRUCTURE_FAILURE })
        assertTrue(result.updatedCampaign.topReasons.any { it.family == InvalidationFamily.FLOW_FAILURE })
    }

    @Test
    fun `should de-risk instead of cancelling when a slice is already filled`() {
        // given the same moderate break but with slice 1 already filled
        val board = Fixtures.campaign(filledIndices = setOf(0))

        // when invalidation is evaluated
        val result = engine.evaluateCampaign(
            board,
            Fixtures.asset(lastPrice = 97.0),
            Fixtures.evidence(takerBuyRatio = 0.30),
            levelMap
        )

        // then the position is kept and only unfilled slices stop resting
        assertEquals(CampaignState.REDUCED, result.updatedCampaign.status)
        assertTrue(result.updatedCampaign.entryLadder[0].isFilled)
        assertFalse(result.updatedCampaign.entryLadder[1].isResting)
    }

    @Test
    fun `should hard-flatten and start a cooldown at the hard threshold`() {
        // given structure failure, an OI/price contradiction and no taker response
        val board = Fixtures.campaign(prices = listOf(100.0, 99.0, 98.0))
        val asset = Fixtures.asset(lastPrice = 97.0, priceChange24h = -2.0, atr5m = 1.0)
        val evidence = Fixtures.evidence(oiDelta1hPct = 1.5, takerBuyRatio = 0.30)
        val startedAt = System.currentTimeMillis()

        // when invalidation is evaluated
        val result = engine.evaluateCampaign(board, asset, evidence, levelMap)

        // then 35 + 25 + 18 = 78 trips the hard threshold
        assertEquals(InvalidationAction.HARD_FLATTEN_COOLDOWN, result.triggerAction)
        assertEquals(78, result.updatedCampaign.invalidationScore)
        assertEquals(CampaignState.CANCELLED, result.updatedCampaign.status)
        assertTrue(result.updatedCampaign.entryLadder.none { it.isResting })
        assertTrue(result.updatedCampaign.cooldownUntilTs > startedAt)
    }

    @Test
    fun `should flag stagnation when a resting board sits inside its zone past the budget`() {
        // given a primary board created 300 minutes ago and still inside its zone
        val board = Fixtures.campaign(
            prices = listOf(100.0, 99.0, 98.0),
            createdAt = System.currentTimeMillis() - 300L * 60_000L
        )

        // when evidence is neutral and price is still in the zone
        val result = engine.evaluateCampaign(board, Fixtures.asset(lastPrice = 99.0), Fixtures.evidence(), levelMap)

        // then §28 time invalidation is flagged with the rest of the board untouched
        assertEquals(InvalidationAction.NO_ACTION, result.triggerAction)
        assertEquals(20, result.updatedCampaign.invalidationScore)
        assertTrue(result.updatedCampaign.topReasons.any { it.family == InvalidationFamily.TIME_EXPIRATION })
        assertTrue(result.updatedCampaign.entryLadder.all { it.isResting })
    }

    @Test
    fun `should deny rearm before the cooldown lapses`() {
        // given a cancelled board still inside its cooldown
        val board = Fixtures.campaign(status = CampaignState.CANCELLED, cooldownUntilTs = 1_000L)

        // when rearm is evaluated before the cooldown ends
        val result = engine.evaluateRearm(board, levelMap, Fixtures.asset(), nowTs = 500L)

        // then the board stays withdrawn
        assertEquals(InvalidationAction.NO_ACTION, result.triggerAction)
        assertEquals(CampaignState.CANCELLED, result.updatedCampaign.status)
    }

    @Test
    fun `should rearm a cancelled empty board while its level is still detected`() {
        // given a cancelled board past its cooldown with nothing filled
        val board = Fixtures.campaign(status = CampaignState.CANCELLED, cooldownUntilTs = 1_000L)
        val zones = listOf(Fixtures.zone(ZoneType.LTF_PIVOT, 98.8, 99.2))

        // when the level is still mapped at the ladder centre
        val result = engine.evaluateRearm(
            board,
            Fixtures.levelMap(zones = zones),
            Fixtures.asset(atr5m = 1.0),
            nowTs = 5_000L
        )

        // then the same family is re-armed at the same level
        assertEquals(InvalidationAction.REARM_CAMPAIGN, result.triggerAction)
        assertEquals(CampaignState.STAGED, result.updatedCampaign.status)
        assertEquals(12, result.updatedCampaign.invalidationScore)
        assertTrue(result.updatedCampaign.entryLadder.all { it.isResting })
    }

    @Test
    fun `should deny rearm when the original level has decayed away`() {
        // given a cancelled board whose level is no longer near the ladder
        val board = Fixtures.campaign(status = CampaignState.CANCELLED, cooldownUntilTs = 1_000L)
        val zones = listOf(Fixtures.zone(ZoneType.HTF_RESISTANCE, 119.0, 121.0))

        // when rearm is evaluated
        val result = engine.evaluateRearm(
            board,
            Fixtures.levelMap(zones = zones),
            Fixtures.asset(atr5m = 1.0),
            nowTs = 5_000L
        )

        // then the board is not resurrected
        assertEquals(InvalidationAction.NO_ACTION, result.triggerAction)
        assertEquals(CampaignState.CANCELLED, result.updatedCampaign.status)
    }

    @Test
    fun `should wake a suppressed mirror after a hard-invalidated trap`() {
        // given a trapped board hard-invalidated at 80 and its suppressed mirror
        val trapped = Fixtures.campaign(
            id = "SOL_SUPPORT",
            status = CampaignState.CANCELLED,
            invalidationScore = 80
        )
        val mirror = Fixtures.campaign(
            id = "SOL_RESISTANCE",
            family = CampaignFamily.RESISTANCE_SHORT,
            status = CampaignState.SUPPRESSED
        )

        // when mirror activation is evaluated
        val activated = engine.activateMirrorOnTrap(trapped, mirror)

        // then the mirror arms as a defensive board at half size
        assertNotNull(activated)
        assertEquals(CampaignState.STAGED, activated!!.status)
        assertEquals(BoardRole.DEFENSIVE, activated.role)
        assertEquals(0.5, activated.sizeMultiplier, 1e-9)
        assertEquals(mirror.priorityScore + 10.0, activated.priorityScore, 1e-9)
    }

    @Test
    fun `should not activate a mirror without a hard trap`() {
        // given a board invalidated only to 50 (below the hard threshold)
        val soft = Fixtures.campaign(status = CampaignState.CANCELLED, invalidationScore = 50)
        val mirror = Fixtures.campaign(
            id = "SOL_RESISTANCE",
            family = CampaignFamily.RESISTANCE_SHORT,
            status = CampaignState.SUPPRESSED
        )

        // when mirror activation is evaluated
        val activated = engine.activateMirrorOnTrap(soft, mirror)

        // then the mirror stays suppressed
        assertNull(activated)
    }

    @Test
    fun `should promote a healthy secondary board when the primary dies`() {
        // given a healthy secondary board and a dead primary
        val secondary = Fixtures.campaign(
            id = "RECLAIM",
            family = CampaignFamily.RECLAIM_LONG,
            role = BoardRole.SECONDARY,
            invalidationScore = 10
        )

        // when promotion is evaluated
        val promoted = engine.checkBoardPromotion(secondary, isPrimaryDead = true)

        // then it takes the primary role at boosted size and priority
        assertEquals(BoardRole.PRIMARY, promoted.role)
        assertEquals(1.25, promoted.sizeMultiplier, 1e-9)
        assertEquals(secondary.priorityScore + 20.0, promoted.priorityScore, 1e-9)

        // and an unhealthy secondary board is never promoted
        val unhealthy = engine.checkBoardPromotion(secondary.copy(invalidationScore = 45), isPrimaryDead = true)
        assertEquals(BoardRole.SECONDARY, unhealthy.role)
    }
}
