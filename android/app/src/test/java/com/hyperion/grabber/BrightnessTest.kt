package com.hyperion.grabber

import org.junit.Assert.assertEquals
import org.junit.Test

// Exercises the production clampBrightness() used by GrabberViewModel.saveBrightness.
class BrightnessTest {

    @Test fun midRange()     { assertEquals(75,  clampBrightness(75))  }
    @Test fun atZero()       { assertEquals(0,   clampBrightness(0))   }
    @Test fun atHundred()    { assertEquals(100, clampBrightness(100)) }
    @Test fun belowZero()    { assertEquals(0,   clampBrightness(-10)) }
    @Test fun aboveHundred() { assertEquals(100, clampBrightness(150)) }
}
