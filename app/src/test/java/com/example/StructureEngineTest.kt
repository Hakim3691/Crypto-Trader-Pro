package com.example

import com.example.data.model.ZoneType
import com.example.data.remote.BinanceFuturesClient
import com.example.engine.StructureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §8 level detection from real swing structure.
 *
 * Scenario: the compliance review found `StructureEngine` synthesized every zone from
 * ATR offsets around the current price instead of detecting swing highs/lows. These
 * scenarios pin the candle-driven path: zones must land on the pivots the market
 * actually printed (90 / 110 around a 100 price), not on synthetic bands near spot.
 * The ATR-synthesis overload survives only as the documented §5 no-candle fallback.
 */
class StructureEngineTest {

    private val engine = StructureEngine()

    /**
     * A triangular tape that only turns at 110 (highs) and 90 (lows): monotonic ramps
     * produce no intermediate pivots, so any honest swing detection must find exactly
     * those two levels.
     */
    private fun triangleHistory(count: Int): List<BinanceFuturesClient.Candle> =
        (0 until count).map { i ->
            val phase = i % 20
            val price = if (phase < 10) 90.0 + phase * 2.0 else 110.0 - (phase - 10) * 2.0
            Fixtures.candle(i, price, price + 0.5, price - 0.5, price)
        }

    @Test
    fun `should derive HTF zones from detected swings instead of ATR offsets`() {
        // given history that turns only at 90 and 110 around a 100 price
        val candles = triangleHistory(120)
        val asset = Fixtures.asset(lastPrice = 100.0, atr5m = 1.0)

        // when a candle-driven level map is built
        val map = engine.buildLevelMap(asset, candles, candles, candles)

        // then support and resistance sit on the traded pivots
        val support = map.zones.first { it.zoneType == ZoneType.HTF_SUPPORT }
        val resistance = map.zones.first { it.zoneType == ZoneType.HTF_RESISTANCE }
        assertTrue("support centre was ${support.center}", support.center in 85.0..95.0)
        assertTrue("resistance centre was ${resistance.center}", resistance.center in 105.0..115.0)

        // and they are not the synthetic bands (97.0 / 104.2 for this ATR)
        assertTrue("support looked synthesized", support.center < 96.0)
        assertTrue("resistance looked synthesized", resistance.center > 105.5)
    }

    @Test
    fun `should always return the complete six-family zone inventory`() {
        // given no candle history at all
        val asset = Fixtures.asset()

        // when the §5 fallback map is built
        val map = engine.buildLevelMap(asset, emptyList(), emptyList(), emptyList())

        // then every §7 zone family is still present exactly once
        assertEquals(ZoneType.entries.toSet(), map.zones.map { it.zoneType }.toSet())
        assertEquals(ZoneType.entries.size, map.zones.size)
    }

    @Test
    fun `should decay a fast-timeframe zone faster than an HTF zone`() {
        // given a 5m zone and a 4h zone, both fully fresh
        val ltf = Fixtures.zone(ZoneType.LTF_PIVOT, 99.0, 100.0, timeframe = "5m", freshness = 90)
        val htf = Fixtures.zone(ZoneType.HTF_SUPPORT, 95.0, 96.0, timeframe = "4h", freshness = 90)

        // when both are aged ten minutes
        // then the 5m zone loses 15 points and the 4h zone loses less than one
        assertEquals(75, engine.decayZoneFreshness(ltf, elapsedMinutes = 10).freshnessScore)
        assertEquals(89, engine.decayZoneFreshness(htf, elapsedMinutes = 10).freshnessScore)
    }

    @Test
    fun `should floor freshness instead of decaying to zero`() {
        // given a fresh zone and a very old evaluation
        val zone = Fixtures.zone(ZoneType.LTF_PIVOT, 99.0, 100.0, timeframe = "5m", freshness = 90)

        // when it is aged far past its half-life
        val decayed = engine.decayZoneFreshness(zone, elapsedMinutes = 10_000)

        // then the score floors at the operational minimum rather than vanishing
        assertEquals(10, decayed.freshnessScore)
    }

    @Test
    fun `should produce the same level map for the same history`() {
        // given a fixed candle series
        val candles = triangleHistory(120)
        val asset = Fixtures.asset(lastPrice = 100.0, atr5m = 1.0)

        // when the map is built twice
        val first = engine.buildLevelMap(asset, candles, candles, candles)
        val second = engine.buildLevelMap(asset, candles, candles, candles)

        // then detection is deterministic (no random zone placement)
        assertEquals(
            first.zones.map { Triple(it.zoneType, it.priceLow, it.priceHigh) },
            second.zones.map { Triple(it.zoneType, it.priceLow, it.priceHigh) }
        )
    }
}
