package com.example

import com.example.data.model.BoardRole
import com.example.data.model.Campaign
import com.example.data.model.CampaignFamily
import com.example.data.model.CampaignState
import com.example.data.model.LadderSlice
import com.example.data.model.MarketRegime
import com.example.data.model.StopLogic
import com.example.engine.PaperPnl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Feature: paper-wallet accounting (Spec §5).
 *
 * Deterministic PnL/exposure math driven by ladder fills. Sign convention shared with
 * `PaperPnl.realizedPnlAtFlatten`:
 * `realized = direction * (exit - entry) * qty`, with `direction = +1` for longs and
 * `-1` for shorts — so a short profits when the exit price falls below its entry.
 */
class PaperPnlTest {

    /** filled = (entryPrice, qty, notionalUsd) triples marked isFilled; unfilled slices add no exposure. */
    private fun campaign(
        isLong: Boolean,
        filled: List<Triple<Double, Double, Double>> = emptyList(),
        unfilledNotional: Double = 0.0
    ): Campaign {
        val ladder = filled.mapIndexed { i, (price, qty, notional) ->
            LadderSlice(sliceIndex = i + 1, price = price, qty = qty, notionalUsd = notional, isFilled = true, isResting = false)
        } + if (unfilledNotional > 0.0) {
            listOf(
                LadderSlice(
                    sliceIndex = filled.size + 1,
                    price = 100.0,
                    qty = unfilledNotional / 100.0,
                    notionalUsd = unfilledNotional,
                    isFilled = false,
                    isResting = true
                )
            )
        } else emptyList()
        return Campaign(
            id = "T",
            asset = "SOLUSDT",
            family = if (isLong) CampaignFamily.SUPPORT_LONG else CampaignFamily.RESISTANCE_SHORT,
            regime = MarketRegime.BULLISH_CONTINUATION,
            role = BoardRole.PRIMARY,
            priorityScore = 80.0,
            sizeBudgetUsd = ladder.sumOf { it.notionalUsd },
            sizeMultiplier = 1.0,
            entryLadder = ladder,
            stopLogic = StopLogic(hardStopPrice = 0.0),
            targets = emptyList(),
            invalidationScore = 10,
            topReasons = emptyList(),
            status = CampaignState.ACTIVE
        )
    }

    @Test
    fun `should count only filled slices toward open exposure`() {
        // given a long board with a $600 filled slice and a $300 resting slice
        val campaigns = listOf(
            campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0)), unfilledNotional = 300.0)
        )

        // when exposure is measured
        val exposure = PaperPnl.openExposureUsd(campaigns)

        // then the resting slice contributes nothing
        assertEquals(600.0, exposure, 1e-9)
    }

    @Test
    fun `should report zero exposure and zero pnl when nothing is filled`() {
        // given a board whose only slice is still resting
        val campaigns = listOf(campaign(isLong = true, filled = emptyList(), unfilledNotional = 500.0))

        // when exposure, unrealized PnL and flatten PnL are computed
        val exposure = PaperPnl.openExposureUsd(campaigns)
        val unrealized = PaperPnl.unrealizedPnlUsd(campaigns, 151.0)
        val realized = PaperPnl.realizedPnlAtFlatten(campaigns.first(), 148.0)

        // then every figure is flat
        assertEquals(0.0, exposure, 1e-9)
        assertEquals(0.0, unrealized, 1e-9)
        assertEquals(0.0, realized, 1e-9)
    }

    @Test
    fun `should sign unrealized pnl by direction`() {
        // given an identical 4-unit fill held long and short
        val long = listOf(campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0))))
        val short = listOf(campaign(isLong = false, filled = listOf(Triple(150.0, 4.0, 600.0))))

        // when the mark moves one unit above entry (150 -> 151)
        val longPnl = PaperPnl.unrealizedPnlUsd(long, 151.0)
        val shortPnl = PaperPnl.unrealizedPnlUsd(short, 151.0)

        // then the long gains 4.0 and the short loses 4.0
        assertEquals(4.0, longPnl, 1e-9)
        assertEquals(-4.0, shortPnl, 1e-9)
    }

    @Test
    fun `should book a loss for a long and a gain for a short flattened at the stop`() {
        // given 2-unit fills at 150 on both sides
        val long = campaign(isLong = true, filled = listOf(Triple(150.0, 2.0, 300.0)))
        val short = campaign(isLong = false, filled = listOf(Triple(150.0, 2.0, 300.0)))

        // when both are flattened two units below entry (150 -> 148)
        val longPnl = PaperPnl.realizedPnlAtFlatten(long, 148.0)
        val shortPnl = PaperPnl.realizedPnlAtFlatten(short, 148.0)

        // then the long books -4.0 and the short books +4.0
        assertEquals(-4.0, longPnl, 1e-9)
        assertEquals(4.0, shortPnl, 1e-9)
    }

    @Test
    fun `should convert pnl to R using the configured per-campaign risk budget`() {
        // given equity 10_000 with 0.5% risk per campaign (1R = $50) and a -$100 day
        val equity = 10_000.0
        val riskPct = 0.5
        val dayPnl = -100.0

        // when the day is expressed in R
        val rMultiple = PaperPnl.pnlToR(dayPnl, equity, riskPct)

        // then it is -2.0R
        assertEquals(-2.0, rMultiple, 1e-9)

        // and degenerate settings never divide by zero
        assertEquals(0.0, PaperPnl.pnlToR(dayPnl, 0.0, riskPct), 1e-9)
    }

    @Test
    fun `should aggregate exposure across boards and sides`() {
        // given one long board and one short board holding $600 each
        val campaigns = listOf(
            campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0))),
            campaign(isLong = false, filled = listOf(Triple(5.0, 120.0, 600.0)))
        )

        // when exposure is summed
        val exposure = PaperPnl.openExposureUsd(campaigns)

        // then both sides contribute
        assertEquals(1200.0, exposure, 1e-9)
    }
}
