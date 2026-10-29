package com.example

import com.example.data.remote.BinanceFuturesClient
import com.example.engine.ReplayEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §37 replay harness.
 *
 * Scenario: the follow-up audit found the harness emitted canned literals. Replay now
 * walks real candle history tick by tick and computes its own metrics. These scenarios
 * pin the harness's honesty boundaries: it refuses thin history, reports degraded feeds
 * instead of inventing them, and resolves every replayed board deterministically.
 */
class ReplayEngineTest {

    private val engine = ReplayEngine()

    /** A deterministic oscillating tape: 60 candles would cover ~5 hours of 5m bars. */
    private fun history(count: Int): List<BinanceFuturesClient.Candle> = (0 until count).map { i ->
        val open = 100.0 + kotlin.math.sin(i / 3.0) * 2.0
        val close = open + kotlin.math.sin(i / 2.0) * 0.5
        Fixtures.candle(
            index = i,
            open = open,
            high = maxOf(open, close) + 0.4,
            low = minOf(open, close) - 0.4,
            close = close
        )
    }

    @Test
    fun `should refuse to replay when history is shorter than the minimum`() {
        // given only 20 candles of history
        val result = engine.replay(Fixtures.asset(), history(20))

        // then no board is replayed and no ticks are walked
        assertTrue(result.campaigns.isEmpty())
        assertTrue(result.templates.isEmpty())
        assertEquals(0, result.totalTicks)
        assertEquals(0, result.metrics.replayedCampaigns)
    }

    @Test
    fun `should walk every intrabar tick of the supplied history`() {
        // given 60 candles and 12 subdivisions per candle
        val candles = history(60)

        // when the replay runs
        val result = engine.replay(
            Fixtures.asset(),
            candles,
            fundingRatePct = 0.01,
            spreadPct = 0.02
        )

        // then the tick budget is fully consumed
        assertEquals(candles.size * ReplayEngine.TICKS_PER_CANDLE, result.totalTicks)
    }

    @Test
    fun `should replay every planned board exactly once`() {
        // given a tape with crowding and liquidity context available
        // when the replay runs
        val result = engine.replay(
            Fixtures.asset(),
            history(60),
            fundingRatePct = 0.01,
            spreadPct = 0.02
        )

        // then boards were replayed and each id appears once
        assertTrue(result.campaigns.isNotEmpty())
        assertEquals(
            result.campaigns.size,
            result.campaigns.map { it.campaignId }.distinct().size
        )
        assertFalse(result.degradedFeeds)
    }

    @Test
    fun `should resolve a finite PnL for every replayed board`() {
        // given a completed replay
        val result = engine.replay(
            Fixtures.asset(),
            history(60),
            fundingRatePct = 0.01,
            spreadPct = 0.02
        )

        // when the outcomes are inspected
        // then no board is left with an unresolved or non-finite PnL
        assertTrue(result.campaigns.all { !it.pnlUsd.isNaN() && !it.pnlUsd.isInfinite() })
        assertTrue(result.campaigns.all { it.filledNotionalUsd >= 0.0 })
        assertTrue(result.campaigns.all { it.fillCount >= 0 })
        assertTrue(!result.metrics.totalPnlUsd.isNaN() && !result.metrics.totalPnlUsd.isInfinite())
    }

    @Test
    fun `should report degraded feeds when crowding and liquidity context are absent`() {
        // given a replay with no funding or spread feed
        val result = engine.replay(Fixtures.asset(), history(60))

        // then the run is explicitly marked degraded rather than silently neutral
        assertTrue(result.degradedFeeds)
        assertEquals(60 * ReplayEngine.TICKS_PER_CANDLE, result.totalTicks)
    }

    @Test
    fun `should report no degraded feeds when mandatory context is supplied`() {
        // given funding and spread inputs
        val result = engine.replay(
            Fixtures.asset(),
            history(60),
            fundingRatePct = -0.02,
            spreadPct = 0.03
        )

        // then the run is reported as fully specified
        assertFalse(result.degradedFeeds)
    }
}
