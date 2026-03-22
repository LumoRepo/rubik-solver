package com.xmelon.rubik_solver.vision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LabConverterTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun assertIntWithin(actual: Int, tolerance: Int, expected: Int) {
        assertTrue(actual >= expected - tolerance && actual <= expected + tolerance,
            "Expected $actual to be within $tolerance of $expected")
    }

    private fun assertFloatWithin(actual: Float, tolerance: Float, expected: Float) {
        assertTrue(abs(actual - expected) <= tolerance,
            "Expected $actual to be within $tolerance of $expected")
    }

    @Test fun `pure white round-trips within 1 unit`() {
        val white = rgb(255, 255, 255)
        val lab = LabConverter.sRgbToLab(white)
        assertFloatWithin(lab[0], 1.5f, 100f)
        assertFloatWithin(lab[1], 1.5f, 0f)
        assertFloatWithin(lab[2], 1.5f, 0f)
        val back = LabConverter.labToSRgb(lab)
        assertIntWithin((back shr 16) and 0xFF, 2, 255)
        assertIntWithin((back shr 8)  and 0xFF, 2, 255)
        assertIntWithin( back         and 0xFF, 2, 255)
    }

    @Test fun `pure black round-trips`() {
        val black = rgb(0, 0, 0)
        val lab = LabConverter.sRgbToLab(black)
        assertFloatWithin(lab[0], 1f, 0f)
        val back = LabConverter.labToSRgb(lab)
        assertIntWithin((back shr 16) and 0xFF, 2, 0)
    }

    @Test fun `red sRGB produces positive a-star`() {
        val red = rgb(220, 30, 30)
        val lab = LabConverter.sRgbToLab(red)
        assertTrue(lab[1] > 30f)
    }

    @Test fun `blue sRGB produces negative b-star`() {
        val blue = rgb(30, 30, 200)
        val lab = LabConverter.sRgbToLab(blue)
        assertTrue(lab[2] < -20f)
    }

    @Test fun `yellow sRGB produces positive b-star`() {
        val yellow = rgb(220, 200, 20)
        val lab = LabConverter.sRgbToLab(yellow)
        assertTrue(lab[2] > 40f)
    }

    @Test fun `arbitrary color round-trips within 2 units per channel`() {
        val color = rgb(100, 150, 80)
        val lab = LabConverter.sRgbToLab(color)
        val back = LabConverter.labToSRgb(lab)
        assertIntWithin((back shr 16) and 0xFF, 2, 100)
        assertIntWithin((back shr 8)  and 0xFF, 2, 150)
        assertIntWithin( back         and 0xFF, 2, 80)
    }
}
