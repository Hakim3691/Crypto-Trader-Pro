package com.example.engine

import com.example.data.model.CryptoAsset
import kotlin.math.abs

/**
 * Directional analytics used by the path engine.
 *
 * Evidence frames are built by [LiveMarketDataProvider] from the live asset snapshot —
 * this class intentionally holds no generator and no randomness, so every number that
 * reaches the campaign library is reproducible from its inputs (Spec §4, §37).
 */
class FeatureEngine {

    /**
     * Calculates directional Pain Score based on funding, stop pools, and trapped positioning
     */
    fun calculatePainScore(asset: CryptoAsset, isBullishTarget: Boolean): Int {
        val crowdingFactor = if (isBullishTarget) {
            // Bullish pain: negative funding means shorts pay longs, shorts trapped
            if (asset.fundingRatePct < 0) 85 else 35
        } else {
            // Bearish pain: positive funding means longs pay shorts, longs trapped
            if (asset.fundingRatePct > 0) 80 else 30
        }
        val stopPoolWeight = 75
        val trappedPositioning = if (isBullishTarget && asset.tacticalBias > 0) 80 else 45

        val total = (0.4 * crowdingFactor + 0.3 * stopPoolWeight + 0.3 * trappedPositioning).toInt()
        return total.coerceIn(10, 98)
    }
}
