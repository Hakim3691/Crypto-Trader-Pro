package com.example

import com.example.data.remote.BinanceFuturesClient
import com.example.engine.IndicatorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §8 structure detection, §9/§15 bias bands, §11 pain scoring and §10 calibration.
 *
 * Scenario: analytics are pure functions over real candles, so identical history must
 * always yield identical numbers. The compliance review flagged regime/bias as static
 * model fields and calibration as dead code; these scenarios pin the computed contract.
 */
class IndicatorMathTest {

    @Test
    fun `should return no swings when history is shorter than the window`() {
        // given five candles and a three-candle pivot window
        val candles = (0 until 5).map { Fixtures.candle(it, 100.0, 101.0, 99.0, 100.5) }

        // when swings are detected
        // then there is not enough context on either side of any pivot
        assertTrue(IndicatorMath.swingPoints(candles, window = 3).isEmpty())
    }

    @Test
    fun `should detect a swing high and a swing low across adjacent candles`() {
        // given a spike high at index 1 and a capitulation low at index 3
        val candles = listOf(
            Fixtures.candle(0, 9.0, 10.0, 9.0, 9.5),
            Fixtures.candle(1, 10.0, 12.0, 10.0, 11.0),
            Fixtures.candle(2, 11.0, 11.5, 9.0, 9.5),
            Fixtures.candle(3, 9.0, 9.5, 7.0, 7.5),
            Fixtures.candle(4, 8.0, 10.0, 8.0, 9.0)
        )

        // when one-candle pivots are detected
        val swings = IndicatorMath.swingPoints(candles, window = 1)

        // then exactly the high at 12 and the low at 7 are pivots, in candle order
        assertEquals(2, swings.size)
        assertEquals(12.0, swings[0].price, 1e-9)
        assertTrue(swings[0].isHigh)
        assertEquals(1, swings[0].index)
        assertEquals(7.0, swings[1].price, 1e-9)
        assertFalse(swings[1].isHigh)
        assertEquals(3, swings[1].index)
    }

    @Test
    fun `should return zero structural bias for empty history`() {
        // given no candles
        // when bias is computed, then it is neutral rather than throwing or guessing
        assertEquals(0, IndicatorMath.structuralBias(emptyList()))
        assertEquals(0, IndicatorMath.interimBias(emptyList()))
    }

    @Test
    fun `should map scores onto the §15 operational bands at their boundaries`() {
        // given scores at and either side of every band boundary
        // when each is classified
        // then the band table is applied inclusively
        assertEquals(IndicatorMath.BiasBand.STRONG_BULL, IndicatorMath.BiasBand.of(50))
        assertEquals(IndicatorMath.BiasBand.BULL, IndicatorMath.BiasBand.of(49))
        assertEquals(IndicatorMath.BiasBand.BULL, IndicatorMath.BiasBand.of(20))
        assertEquals(IndicatorMath.BiasBand.NEUTRAL, IndicatorMath.BiasBand.of(19))
        assertEquals(IndicatorMath.BiasBand.NEUTRAL, IndicatorMath.BiasBand.of(0))
        assertEquals(IndicatorMath.BiasBand.BEAR, IndicatorMath.BiasBand.of(-20))
        assertEquals(IndicatorMath.BiasBand.BEAR, IndicatorMath.BiasBand.of(-21))
        assertEquals(IndicatorMath.BiasBand.STRONG_BEAR, IndicatorMath.BiasBand.of(-50))
    }

    @Test
    fun `should expose the band priority and size adjustments`() {
        // given the strong bullish band
        val band = IndicatorMath.BiasBand.STRONG_BULL

        // when its operational adjustments are read
        // then priority rises by 8 and size by 20%
        assertEquals(8.0, band.priorityAdj, 1e-9)
        assertEquals(0.20, band.sizeAdj, 1e-9)
    }

    @Test
    fun `should compute ATR from a single candle range`() {
        // given one candle trading an 8..10 range
        val candles = listOf(Fixtures.candle(0, 8.0, 10.0, 8.0, 9.0))

        // when ATR is computed
        // then it is the candle's true range
        assertEquals(2.0, IndicatorMath.atr(candles), 1e-9)
    }

    @Test
    fun `should return zero ATR for empty history`() {
        // given no candles
        // when ATR is computed
        // then it degrades to zero instead of failing
        assertEquals(0.0, IndicatorMath.atr(emptyList()), 1e-9)
    }

    @Test
    fun `should weight funding crowding above momentum alignment in the pain score`() {
        // given extreme negative funding with no momentum, and strong momentum with no funding
        val crowdingOnly = IndicatorMath.painScore(fundingRate = -0.0125, bias = 0)
        val momentumOnly = IndicatorMath.painScore(fundingRate = 0.0, bias = 68)

        // when both are scored
        // then funding crowding dominates (60) over alignment (27)
        assertEquals(60, crowdingOnly)
        assertEquals(27, momentumOnly)
        assertTrue(crowdingOnly > momentumOnly)
    }

    @Test
    fun `should floor and cap the pain score`() {
        // given a flat book and a fully crowded, fully aligned book
        val floor = IndicatorMath.painScore(fundingRate = 0.0, bias = 0)
        val ceiling = IndicatorMath.painScore(fundingRate = 1.0, bias = 100)

        // when each is scored
        // then the score stays inside the operational 10..98 envelope
        assertEquals(10, floor)
        assertEquals(98, ceiling)
    }

    @Test
    fun `should compute calibration defaults when history is absent`() {
        // given no candle history and a 2 bps spread
        // when calibration is computed
        val calibration = IndicatorMath.computeCalibration(
            symbol = "SOLUSDT",
            candles1h = emptyList(),
            candles5m = emptyList(),
            atr5m = 1.0,
            spreadPct = 0.02
        )

        // then every asymmetry is neutral, the half-life defaults to 60m and slippage is 3.5 bps
        assertEquals("SOLUSDT", calibration.symbol)
        assertEquals(1.0, calibration.impulseAsymmetry, 1e-9)
        assertEquals(1.0, calibration.wickAsymmetry, 1e-9)
        assertEquals(1.0, calibration.followThroughAsymmetry, 1e-9)
        assertEquals(1.0, calibration.crowdingElasticity, 1e-9)
        assertEquals(60, calibration.meanReversionHalfLifeMinutes)
        assertEquals(3.5, calibration.typicalSlippageBps, 1e-6)
    }

    @Test
    fun `should detect a downside skew in impulse asymmetry`() {
        // given 1h candles whose up bodies are 2.0 and down bodies are 0.5
        val candles = listOf(
            Fixtures.candle(0, 100.0, 102.5, 99.5, 102.0),
            Fixtures.candle(1, 102.0, 103.0, 101.5, 101.5),
            Fixtures.candle(2, 101.5, 104.0, 101.0, 103.5),
            Fixtures.candle(3, 103.5, 104.0, 103.0, 103.0),
            Fixtures.candle(4, 103.0, 105.5, 102.5, 105.0),
            Fixtures.candle(5, 105.0, 105.5, 104.5, 104.5)
        )

        // when calibration is computed
        val calibration = IndicatorMath.computeCalibration(
            symbol = "SOLUSDT",
            candles1h = candles,
            candles5m = emptyList(),
            atr5m = 1.0,
            spreadPct = 0.02
        )

        // then upward impulse velocity is 4x the downward velocity
        assertEquals(4.0, calibration.impulseAsymmetry, 1e-9)
    }

    @Test
    fun `should stay deterministic for identical candle history`() {
        // given a fixed candle series
        val candles: List<BinanceFuturesClient.Candle> = (0 until 40).map { i ->
            val base = 100.0 + kotlin.math.sin(i / 3.0) * 2.0
            Fixtures.candle(i, base, base + 0.6, base - 0.6, base + 0.2)
        }

        // when the same analytics run twice
        val firstBias = IndicatorMath.structuralBias(candles)
        val secondBias = IndicatorMath.structuralBias(candles)

        // then the result is identical (no hidden randomness)
        assertEquals(firstBias, secondBias)
        assertTrue(firstBias in -100..100)
    }
}
