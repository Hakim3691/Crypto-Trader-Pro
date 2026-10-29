package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CandleDao
import com.example.data.local.CandleEntity
import com.example.data.model.ExecutionMode
import com.example.engine.IndicatorMath
import com.example.engine.UniverseScreener
import com.example.ui.ExecutionModeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candle storage is irrelevant to these scenarios; the loader is injected instead. */
private class NoCandlesDao : CandleDao {
    override suspend fun getCandles(symbol: String, interval: String): List<CandleEntity> = emptyList()
    override suspend fun getLatestFetchedAtMs(symbol: String, interval: String): Long? = null
    override suspend fun insertCandles(candles: List<CandleEntity>) = Unit
    override suspend fun clearCandles(symbol: String, interval: String) = Unit
}

/**
 * Feature: Spec §4 candle horizons and §9/§15 strategic bias.
 *
 * Scenario: the follow-up audit found only 5m/1h/4h were loaded, so the 1m execution tape,
 * 15m tactical context and 1d strategic context never reached the analysers. The loader
 * seam lets a scenario record exactly which horizons the screener requests.
 */
class TimeframeHorizonTest {

    private val screener = UniverseScreener(NoCandlesDao())

    private fun closeFor(interval: String): Double = when (interval) {
        "1m" -> 1.0
        "5m" -> 5.0
        "15m" -> 15.0
        "1h" -> 60.0
        "4h" -> 240.0
        "1d" -> 1440.0
        else -> -1.0
    }

    @Test
    fun `should request every §4 candle horizon`() = runBlocking {
        // given a screener and a recording candle loader
        val requested = mutableListOf<String>()

        // when the decision timeframe set is loaded
        val frames = screener.loadTimeframes("SOLUSDT") { _, interval, _ ->
            requested += interval
            listOf(Fixtures.candle(0, 1.0, 2.0, 0.5, closeFor(interval)))
        }

        // then all six §4 horizons are requested — including the 1m tape and 1d context
        assertEquals(
            setOf("1m", "5m", "15m", "1h", "4h", "1d"),
            requested.toSet()
        )
        assertEquals(6, requested.size)

        // and each loaded series lands on its own field
        assertEquals(1.0, frames.candles1m.single().close, 1e-9)
        assertEquals(5.0, frames.candles5m.single().close, 1e-9)
        assertEquals(15.0, frames.candles15m.single().close, 1e-9)
        assertEquals(60.0, frames.candles1h.single().close, 1e-9)
        assertEquals(240.0, frames.candles4h.single().close, 1e-9)
        assertEquals(1440.0, frames.candles1d.single().close, 1e-9)
    }

    @Test
    fun `should blend daily context into the strategic bias`() {
        // given flat 4H history and a rising 1D series
        val flat = (0 until 40).map { Fixtures.candle(it, 100.0, 100.5, 99.5, 100.0) }
        val rising = (0 until 40).map { i ->
            val price = 90.0 + i * 0.5
            Fixtures.candle(i, price - 0.25, price + 0.25, price - 0.5, price)
        }

        // when strategic bias is computed with and without daily data
        val htfOnly = IndicatorMath.structuralBias(flat)
        val blended = IndicatorMath.strategicBias(flat, rising)
        val degraded = IndicatorMath.strategicBias(flat, emptyList())

        // then the 1D context lifts the score above the 4H-only reading
        assertTrue("blended $blended should exceed htf-only $htfOnly", blended > htfOnly)
        assertTrue(blended > 0)

        // and the 4H reading alone is used when daily history is missing
        assertEquals(htfOnly, degraded)
    }
}

/**
 * Feature: Spec §4 basis derivation from the premium index.
 *
 * Scenario: the audit found `basisPremiumPct` fell back to 0.0 whenever CoinGlass was
 * unavailable, even though Binance's premium index carries index and mark prices. A flat
 * 0.0 is a fabricated reading; the derivation must either compute a real premium or
 * report that no basis is available.
 */
class BasisDerivationTest {

    private val screener = UniverseScreener(NoCandlesDao())

    @Test
    fun `should prefer the coinglass basis when the feed answers`() {
        // given a CoinGlass basis alongside premium-index prices
        // when basis is derived, then CoinGlass wins outright
        assertEquals(
            12.5,
            screener.basisPremiumPctFrom(12.5, indexPrice = 100.0, markPrice = 100.5)!!,
            1e-9
        )
    }

    @Test
    fun `should derive basis from the premium index instead of falling back to zero`() {
        // given no CoinGlass basis but a 0.5% premium (mark 100.5 over index 100)
        // when basis is derived
        val derived = screener.basisPremiumPctFrom(null, indexPrice = 100.0, markPrice = 100.5)

        // then it is the annualized premium (0.5% x 365), never a bare zero
        assertEquals(182.5, derived!!, 1e-6)
        assertTrue("basis must not be a fabricated flat zero", derived != 0.0)
    }

    @Test
    fun `should sign a negative basis when mark trades below index`() {
        // given a discount of 0.5%
        // when basis is derived, then it is negative rather than clamped to zero
        assertEquals(
            -182.5,
            screener.basisPremiumPctFrom(null, indexPrice = 100.0, markPrice = 99.5)!!,
            1e-6
        )
    }

    @Test
    fun `should report no basis when neither feed has usable prices`() {
        // given no CoinGlass basis and no premium-index prices
        // when basis is derived, then it is explicitly unavailable
        assertNull(screener.basisPremiumPctFrom(null, indexPrice = null, markPrice = null))
        assertNull(screener.basisPremiumPctFrom(null, indexPrice = 0.0, markPrice = 100.5))
        assertNull(screener.basisPremiumPctFrom(null, indexPrice = 100.0, markPrice = null))
    }
}

/**
 * Feature: Spec §36 execution-mode persistence.
 *
 * Scenario: the operator picks a mode, the process dies, and the app relaunches. The
 * audit measured a reset to PAPER every launch. The mode must round-trip through
 * SharedPreferences, while a live mode stays gated behind an unlocked session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionModePersistenceTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ExecutionModeStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `should round-trip a saved mode across a restart`() {
        // given the operator selected shadow mode
        ExecutionModeStore.save(prefs, ExecutionMode.SHADOW)

        // when the process restarts and the mode is restored
        val restored = ExecutionModeStore.load(prefs, hasActiveSession = false)

        // then the chosen mode survives instead of resetting to paper
        assertEquals(ExecutionMode.SHADOW, restored)
    }

    @Test
    fun `should restore a live mode only when a session is unlocked`() {
        // given a persisted live mode
        ExecutionModeStore.save(prefs, ExecutionMode.SCALED_LIVE)

        // when restored without credentials, then it downgrades to the safe paper default
        assertEquals(ExecutionMode.PAPER, ExecutionModeStore.load(prefs, hasActiveSession = false))

        // and with an unlocked session it restores exactly
        assertEquals(
            ExecutionMode.SCALED_LIVE,
            ExecutionModeStore.load(prefs, hasActiveSession = true)
        )
    }

    @Test
    fun `should default to paper when nothing was stored`() {
        // given a fresh install with no stored mode
        // when the mode is loaded, then the safe default applies
        assertEquals(ExecutionMode.PAPER, ExecutionModeStore.load(prefs, hasActiveSession = true))
    }

    @Test
    fun `should ignore a corrupt stored mode`() {
        // given a stored value that is not a valid mode
        prefs.edit().putString(ExecutionModeStore.KEY_EXECUTION_MODE, "NOT_A_MODE").commit()

        // when the mode is loaded, then it falls back to paper rather than crashing
        assertEquals(ExecutionMode.PAPER, ExecutionModeStore.load(prefs, hasActiveSession = true))
    }
}
