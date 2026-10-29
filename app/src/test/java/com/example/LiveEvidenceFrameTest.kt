package com.example

import com.example.data.local.CandleDao
import com.example.data.local.CandleEntity
import com.example.data.model.LiquidationContext
import com.example.engine.LiveMarketDataProvider
import com.example.engine.UniverseScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: Spec §4 live-display honesty and §6 universe boundaries.
 *
 * Scenario: evidence frames are built from the live asset snapshot only. The compliance
 * review found the frames were random draws; these scenarios pin that identical inputs
 * produce identical evidence, that the liquidation feed is carried through, and that the
 * mid-cap filter matches the §6 volume/spread bounds exactly.
 */
class LiveEvidenceFrameTest {

    /** The screener is only a construction dependency here; evidence is pure. */
    private class NoCandlesDao : CandleDao {
        override suspend fun getCandles(symbol: String, interval: String): List<CandleEntity> = emptyList()
        override suspend fun getLatestFetchedAtMs(symbol: String, interval: String): Long? = null
        override suspend fun insertCandles(candles: List<CandleEntity>) = Unit
        override suspend fun clearCandles(symbol: String, interval: String) = Unit
    }

    private val provider = LiveMarketDataProvider(UniverseScreener(NoCandlesDao()))

    @Test
    fun `should build identical evidence for an identical snapshot`() {
        // given a live snapshot with OI, basis and bullish tactical bias
        val asset = Fixtures.asset(
            tacticalBias = 60,
            basisPremiumPct = 0.08,
            openInterestUsd = 5_000_000.0
        )

        // when the frame is built twice
        val first = provider.buildEvidenceFrame(asset)
        val second = provider.buildEvidenceFrame(asset)

        // then only the wall-clock timestamp may differ (no random draws)
        assertEquals(first.copy(timestamp = 0L), second.copy(timestamp = 0L))
    }

    @Test
    fun `should derive OI, basis and taker evidence from the snapshot`() {
        // given a snapshot with OI, an 8 bps premium and bullish bias
        val asset = Fixtures.asset(
            tacticalBias = 60,
            basisPremiumPct = 0.08,
            openInterestUsd = 5_000_000.0
        )

        // when the evidence frame is built
        val frame = provider.buildEvidenceFrame(asset)

        // then each field reflects the live inputs
        assertEquals(0.6, frame.oiDelta1hPct, 1e-9)
        assertEquals(8.0, frame.basisDeltaBps, 1e-6)
        assertEquals(0.62, frame.takerBuyRatio, 1e-9)
        assertEquals(0.72, frame.acceptanceQuality, 1e-9)
        assertEquals(42, frame.trapProbabilityPct)
        assertTrue(frame.recentImpulseQuality.contains("Taker Buy Dominance"))
    }

    @Test
    fun `should report no open-interest expansion without an OI feed`() {
        // given a snapshot whose OI feed returned nothing
        val asset = Fixtures.asset(openInterestUsd = 0.0)

        // when the evidence frame is built
        val frame = provider.buildEvidenceFrame(asset)

        // then OI expansion is reported as flat rather than invented
        assertEquals(0.0, frame.oiDelta1hPct, 1e-9)
    }

    @Test
    fun `should carry the asset liquidation context into the frame`() {
        // given 24h liquidations of $1.0M long and $250k short
        val context = LiquidationContext(
            longLiquidatedUsd24h = 1_000_000.0,
            shortLiquidatedUsd24h = 250_000.0,
            lastUpdatedMs = 1_700_000_000_000L
        )
        val asset = Fixtures.asset(liquidationContext = context)

        // when the frame is built
        val frame = provider.buildEvidenceFrame(asset)

        // then the liquidation feed reaches the invalidation/evidence layer unchanged
        assertEquals(context, frame.liquidationContext)
    }

    @Test
    fun `should report a balanced tape for a neutral bias`() {
        // given a snapshot with no directional edge
        val asset = Fixtures.asset(tacticalBias = 10)

        // when the frame is built
        val frame = provider.buildEvidenceFrame(asset)

        // then the tape reads balanced at a 50% taker-buy share
        assertEquals(0.5, frame.takerBuyRatio, 1e-9)
        assertEquals("Balanced Tape (Live)", frame.recentImpulseQuality)
    }

    @Test
    fun `should include a symbol only inside the §6 volume and spread bounds`() {
        // given snapshots at and outside the mid-cap boundaries
        val atFloor = Fixtures.asset(quoteVolume24h = 50_000_000.0, orderBookSpreadPct = 0.09)
        val atCeiling = Fixtures.asset(quoteVolume24h = 750_000_000.0, orderBookSpreadPct = 0.09)
        val belowFloor = Fixtures.asset(quoteVolume24h = 49_999_999.0)
        val aboveCeiling = Fixtures.asset(quoteVolume24h = 800_000_000.0)
        val tooWide = Fixtures.asset(quoteVolume24h = 100_000_000.0, orderBookSpreadPct = 0.10)

        // when eligibility is evaluated, then the range is inclusive and the spread is strict
        assertTrue(atFloor.isMidCapQualified)
        assertTrue(atCeiling.isMidCapQualified)
        assertFalse(belowFloor.isMidCapQualified)
        assertFalse(aboveCeiling.isMidCapQualified)
        assertFalse(tooWide.isMidCapQualified)
    }

    @Test
    fun `should pin the §6 screening boundaries to the specification`() {
        // given the screener's §6 filter constants (the live universe gate)
        // when they are compared with the spec, then volume is $50M-$750M
        assertEquals(50_000_000.0, UniverseScreener.MIN_QUOTE_VOLUME_USD, 1e-9)
        assertEquals(750_000_000.0, UniverseScreener.MAX_QUOTE_VOLUME_USD, 1e-9)

        // and the depth gate stays strictly under 0.10%
        assertEquals(0.10, UniverseScreener.MAX_SPREAD_PCT, 1e-9)
    }
}
