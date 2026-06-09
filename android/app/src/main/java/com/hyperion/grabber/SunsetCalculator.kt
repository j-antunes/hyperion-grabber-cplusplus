package com.hyperion.grabber

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.tan

object SunsetCalculator {

    fun getSunsetHour(): Int {
        val cal = Calendar.getInstance()
        return computeSunsetHour(
            // getOffset includes DST; rawOffset would be an hour off all summer
            tzOffsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0,
            dayOfYear     = cal.get(Calendar.DAY_OF_YEAR)
        )
    }

    // Pure function — testable without system timezone.
    // tzOffsetHours: e.g. -5.0 for EST, +1.0 for CET.
    // dayOfYear: 1–365.
    internal fun computeSunsetHour(tzOffsetHours: Double, dayOfYear: Int): Int {
        val longitude = tzOffsetHours * 15.0
        val latitude  = 45.0

        val decl = 23.45 * sin((360.0 / 365.0 * (dayOfYear - 81)).toRadians())

        val cosH = -tan(latitude.toRadians()) * tan(decl.toRadians())
        if (cosH < -1.0) return 21
        if (cosH >  1.0) return 16

        val hourAngle    = Math.toDegrees(acos(cosH))
        val solarNoonUtc = 12.0 - longitude / 15.0
        val sunsetUtc    = solarNoonUtc + hourAngle / 15.0
        val sunsetLocal  = sunsetUtc + tzOffsetHours

        return sunsetLocal.toInt().coerceIn(14, 22)
    }

    private fun Double.toRadians() = Math.toRadians(this)
}
