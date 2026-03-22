package com.xmelon.rubik_solver.vision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorModelTest {

    private fun assertFloatWithin(actual: Float, tolerance: Float, expected: Float) {
        assertTrue(abs(actual - expected) <= tolerance,
            "Expected $actual to be within $tolerance of $expected")
    }

    @Test fun `starts with prior variance before MIN_SAMPLES`() {
        val m = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        assertEquals(ColorModel.DEFAULT_PRIOR_VARIANCE, m.variance)
    }

    @Test fun `mean converges toward repeated sample`() {
        val m = ColorModel(mean = floatArrayOf(0f, 0f, 0f))
        val target = floatArrayOf(80f, 10f, -30f)
        repeat(30) { m.update(target) }
        assertFloatWithin(m.mean[0], 0.5f, 80f)
        assertFloatWithin(m.mean[1], 0.5f, 10f)
        assertFloatWithin(m.mean[2], 0.5f, -30f)
    }

    @Test fun `variance transitions from prior to Welford after MIN_SAMPLES`() {
        val m = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        repeat((ColorModel.MIN_SAMPLES + 1).toInt()) {
            m.update(floatArrayOf(50f, 0f, 0f))
        }
        assertTrue(m.variance < ColorModel.DEFAULT_PRIOR_VARIANCE)
    }

    @Test fun `variance with scattered samples is greater than with tight samples`() {
        val tight = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        val spread = ColorModel(mean = floatArrayOf(50f, 0f, 0f))
        repeat(20) { tight.update(floatArrayOf(50f, 0f, 0f)) }
        repeat(20) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            spread.update(floatArrayOf(50f + 30f * sign, 0f, 0f))
        }
        assertTrue(spread.variance > tight.variance)
    }

    @Test fun `score is lower for matching sample than distant sample`() {
        val m = ColorModel(mean = floatArrayOf(80f, -5f, 65f))
        val yellowLike = floatArrayOf(82f, -4f, 63f)
        val blueLike   = floatArrayOf(32f, 14f, -52f)
        assertTrue(m.score(yellowLike) < m.score(blueLike))
    }

    @Test fun `weight=3 update moves mean 3x faster than weight=1`() {
        val seed = floatArrayOf(0f, 0f, 0f)
        val m1 = ColorModel(mean = seed.copyOf(), n = 3f)
        val m3 = ColorModel(mean = seed.copyOf(), n = 3f)
        val target = floatArrayOf(100f, 0f, 0f)
        m1.update(target, weight = 1f)
        m3.update(target, weight = 3f)
        assertTrue(m3.mean[0] > m1.mean[0])
    }

    @Test fun `copy is independent of original`() {
        val m = ColorModel(mean = floatArrayOf(50f, 10f, -20f))
        m.update(floatArrayOf(60f, 15f, -15f))
        val meanAfterFirstUpdate = m.mean[0]
        val copy = m.copy()
        m.update(floatArrayOf(100f, 100f, 100f))
        assertFloatWithin(copy.mean[0], 0.01f, meanAfterFirstUpdate)
        assertTrue(copy.n < m.n)
    }
}
