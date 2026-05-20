package com.hyperion.grabber

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionTest {

    // pi3a real-world values from the JSON API:
    // minZoneW ≈ 0.0095, minZoneH ≈ 0.0185 → expected 216×112
    @Test
    fun pi3aLedLayoutProducesCorrectResolution() {
        val zones = buildZones(
            hMin = 0.0, hMax = 0.0095,  // narrowest horizontal zone
            vMin = 0.0, vMax = 0.0185   // narrowest vertical zone
        )
        val (w, h) = computeRecommendedResolution(zones)
        assertEquals(216, w)
        assertEquals(112, h)
    }

    // Minimal case: one LED covering the full image → 1×1 zone → resolution = 8×8 (min after roundUp8)
    @Test
    fun singleFullCoverageZoneGivesMinimumResolution() {
        val zones = listOf(1.0 to 1.0)
        val (w, h) = computeRecommendedResolution(zones)
        assertEquals(8, w)
        assertEquals(8, h)
    }

    // Degenerate zones (≤ 0.001) must be ignored
    @Test
    fun degenerateZonesAreIgnored() {
        val zones = listOf(
            0.0001 to 0.0001,  // too small, ignored
            0.5    to 0.5      // valid
        )
        val (w, h) = computeRecommendedResolution(zones)
        // ceil(2 / 0.5) = 4 → roundUp8 = 8
        assertEquals(8, w)
        assertEquals(8, h)
    }

    // All degenerate zones → fallback (64×36)
    @Test
    fun allDegenerateZonesFallsBackToDefault() {
        val zones = listOf(0.0 to 0.0, 0.0001 to 0.0001)
        val (w, h) = computeRecommendedResolution(zones)
        assertEquals(64, w)
        assertEquals(36, h)
    }

    // Resolution is capped at maxDim (default 256)
    @Test
    fun resolutionIsCappedAtMaxDim() {
        // Very tiny zone → rawW would exceed 256
        val zones = listOf(0.005 to 0.005)
        val (w, h) = computeRecommendedResolution(zones, maxDim = 256)
        assertEquals(256, w)
        assertEquals(256, h)
    }

    // Custom maxDim is respected
    @Test
    fun customMaxDimIsRespected() {
        val zones = listOf(0.005 to 0.005)
        val (w, h) = computeRecommendedResolution(zones, maxDim = 128)
        assertEquals(128, w)
        assertEquals(128, h)
    }

    // Output is always a multiple of 8
    @Test
    fun outputIsAlwaysMultipleOfEight() {
        // minZoneW = 0.07 → rawW = ceil(2/0.07) = ceil(28.57) = 29 → roundUp8 = 32
        val zones = listOf(0.07 to 0.07)
        val (w, h) = computeRecommendedResolution(zones)
        assertEquals(0, w % 8)
        assertEquals(0, h % 8)
        assertEquals(32, w)
        assertEquals(32, h)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildZones(hMin: Double, hMax: Double, vMin: Double, vMax: Double) =
        listOf((hMax - hMin) to (vMax - vMin))
}
