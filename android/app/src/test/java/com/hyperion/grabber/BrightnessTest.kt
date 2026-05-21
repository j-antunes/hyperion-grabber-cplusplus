package com.hyperion.grabber

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessTest {

    private fun clamp(v: Int) = v.coerceIn(0, 100)

    @Test fun midRange()     { assertEquals(75,  clamp(75))  }
    @Test fun atZero()       { assertEquals(0,   clamp(0))   }
    @Test fun atHundred()    { assertEquals(100, clamp(100)) }
    @Test fun belowZero()    { assertEquals(0,   clamp(-10)) }
    @Test fun aboveHundred() { assertEquals(100, clamp(150)) }
}
