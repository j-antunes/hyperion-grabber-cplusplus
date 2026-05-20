package com.hyperion.grabber

import org.junit.Assert.assertTrue
import org.junit.Test

class SunsetCalculatorTest {

    // Summer solstice (day 172) at UTC+1 (CET) → sunset around 19–20h at 45°N latitude
    @Test
    fun summerSolsticeCentralEuropeSunsetIsLaterThanWinter() {
        val summer = SunsetCalculator.computeSunsetHour(tzOffsetHours = 1.0, dayOfYear = 172)
        val winter = SunsetCalculator.computeSunsetHour(tzOffsetHours = 1.0, dayOfYear = 355)
        assertTrue("Summer sunset ($summer) should be later than winter ($winter)", summer > winter)
        assertTrue("Summer sunset at 45°N UTC+1 expected 18–22h, got $summer", summer in 18..22)
    }

    // Winter solstice (day 355) at UTC+1 (CET) → sunset around 15–17h at 45°N
    @Test
    fun winterSolsticeCentralEuropeSunsetIsEarly() {
        val hour = SunsetCalculator.computeSunsetHour(tzOffsetHours = 1.0, dayOfYear = 355)
        assertTrue("Expected 14–17h, got $hour", hour in 14..17)
    }

    // Summer solstice at UTC-5 (EST) → should be later than winter
    @Test
    fun summerSolsticeEasternUSSunsetIsLaterThanWinter() {
        val summer = SunsetCalculator.computeSunsetHour(tzOffsetHours = -5.0, dayOfYear = 172)
        val winter = SunsetCalculator.computeSunsetHour(tzOffsetHours = -5.0, dayOfYear = 355)
        assertTrue("Summer ($summer) should be later than winter ($winter)", summer > winter)
    }

    // Equinox (day 81) → sunset should be near 18:00 local regardless of timezone
    @Test
    fun equinoxSunsetIsNearSixPm() {
        for (offset in listOf(-8.0, -5.0, 0.0, 1.0, 5.5, 9.0)) {
            val hour = SunsetCalculator.computeSunsetHour(tzOffsetHours = offset, dayOfYear = 81)
            assertTrue("offset=$offset: expected ~18h, got $hour", hour in 17..19)
        }
    }

    // Output is always within the clamped range [14, 22]
    @Test
    fun outputIsAlwaysWithinClampedRange() {
        for (day in 1..365 step 10) {
            for (offset in listOf(-12.0, -5.0, 0.0, 5.5, 12.0)) {
                val hour = SunsetCalculator.computeSunsetHour(tzOffsetHours = offset, dayOfYear = day)
                assertTrue("day=$day offset=$offset: $hour out of [14,22]", hour in 14..22)
            }
        }
    }
}
