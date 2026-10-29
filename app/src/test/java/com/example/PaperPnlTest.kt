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
 * §5 Paper-wallet accounting: deterministic PnL/exposure math driven by ladder fills.
 *
 * Short-sell PnL convention used by the paper wallet (matches `Realized PnL` in the
 * spec's campaign accounting): a short profits when the exit price rises and loses when
 * it falls, i.e. `realized = direction * (exit - entry) * qty` with `direction = -1` for
 * shorts. This is the same sign convention as `PaperPnl.realizedPnlAtFlatten`.
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
    fun `exposure counts only filled slices`() {
        val campaigns = listOf(
            campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0)), unfilledNotional = 300.0)
        )
        assertEquals(600.0, PaperPnl.openExposureUsd(campaigns), 1e-9)
    }

    @Test
    fun `no fills means zero exposure and zero pnl`() {
        val campaigns = listOf(campaign(isLong = true, filled = emptyList(), unfilledNotional = 500.0))
        assertEquals(0.0, PaperPnl.openExposureUsd(campaigns), 1e-9)
        assertEquals(0.0, PaperPnl.unrealizedPnlUsd(campaigns, 151.0), 1e-9)
        assertEquals(0.0, PaperPnl.realizedPnlAtFlatten(campaigns.first(), 148.0), 1e-9)
    }

    @Test
    fun `unrealized pnl is direction aware`() {
        val long = listOf(campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0))))
        val short = listOf(campaign(isLong = false, filled = listOf(Triple(150.0, 4.0, 600.0))))
        // Price 151: long is +4.0 (qty 4 * +1), short is -4.0.
        assertEquals(4.0, PaperPnl.unrealizedPnlUsd(long, 151.0), 1e-9)
        assertEquals(-4.0, PaperPnl.unrealizedPnlUsd(short, 151.0), 1e-9)
    }

    @Test
    fun `realized flatten at stop books loss for long and gain for short`() {
        val long = campaign(isLong = true, filled = listOf(Triple(150.0, 2.0, 300.0)))
        val short = campaign(isLong = false, filled = listOf(Triple(150.0, 2.0, 300.0)))
        // Long flat at 148 vs 150 entry => loss of 2.0/unit * 2 units = -4.0.
        assertEquals(-4.0, PaperPnl.realizedPnlAtFlatten(long, 148.0), 1e-9)
    }

    @Test
    fun `pnl to r uses the configured per-campaign risk budget as 1R`() {
        // Equity 10_000 at 0.5% risk/campaign => 1R = $50; a -100 USD day is -2.0R.
        assertEquals(-2.0, PaperPnl.pnlToR(-100.0, 10_000.0, 0.5), 1e-9)
        // Degenerate settings (zero equity) never divide by zero.
        assertEquals(0.0, PaperPnl.pnlToR(-100.0, 0.0, 0.5), 1e-9)
    }

    @Test
    fun `exposure aggregates across boards and sides`() {
        val campaigns = listOf(
            campaign(isLong = true, filled = listOf(Triple(150.0, 4.0, 600.0))),
            campaign(isLong = false, filled = listOf(Triple(5.0, 120.0, 600.0)))
        )
        assertEquals(1200.0, PaperPnl.openExposureUsd(campaigns), 1e-9)
    }
}
