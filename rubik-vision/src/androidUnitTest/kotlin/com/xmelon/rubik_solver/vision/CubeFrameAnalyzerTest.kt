package com.xmelon.rubik_solver.vision

import com.google.common.truth.Truth.assertThat
import com.xmelon.rubik_solver.model.Face
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Unit tests for CubeFrameAnalyzer.
 *
 * labMedian is private; it is exercised via reflection so we can test its
 * arithmetic without instantiating Android framework components.
 */
class CubeFrameAnalyzerTest {

    // ─── helpers ────────────────────────────────────────────────────────────

    private val analyzer = CubeFrameAnalyzer()

    /** Reflective accessor for the private labMedian method. */
    private fun invokeLabMedian(labs: List<FloatArray>, dest: FloatArray) {
        val method: Method = CubeFrameAnalyzer::class.java
            .getDeclaredMethod("labMedian", List::class.java, FloatArray::class.java)
        method.isAccessible = true
        method.invoke(analyzer, labs, dest)
    }

    // ─── E4a: labMedian even-n test ─────────────────────────────────────────

    @Test
    fun `labMedian of four LAB values returns average of two middle values per channel`() {
        // Input (already sorted per channel so the behaviour is deterministic):
        //   { 1,2,3 }, { 3,4,5 }, { 5,6,7 }, { 7,8,9 }
        // Per-channel sorted values:
        //   ch0: [1,3,5,7]  → median = (3+5)/2 = 4
        //   ch1: [2,4,6,8]  → median = (4+6)/2 = 5
        //   ch2: [3,5,7,9]  → median = (5+7)/2 = 6
        val labs = listOf(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(3f, 4f, 5f),
            floatArrayOf(5f, 6f, 7f),
            floatArrayOf(7f, 8f, 9f)
        )
        val dest = FloatArray(3)
        invokeLabMedian(labs, dest)

        assertThat(dest[0]).isWithin(1e-4f).of(4f)
        assertThat(dest[1]).isWithin(1e-4f).of(5f)
        assertThat(dest[2]).isWithin(1e-4f).of(6f)
    }

    @Test
    fun `labMedian of one value returns that value`() {
        val labs = listOf(floatArrayOf(42f, -10f, 5f))
        val dest = FloatArray(3)
        invokeLabMedian(labs, dest)

        assertThat(dest[0]).isWithin(1e-4f).of(42f)
        assertThat(dest[1]).isWithin(1e-4f).of(-10f)
        assertThat(dest[2]).isWithin(1e-4f).of(5f)
    }

    @Test
    fun `labMedian of odd-n values returns the middle element per channel`() {
        // 3 values; sorted per channel the middle (index 1) is returned.
        val labs = listOf(
            floatArrayOf(10f, 20f, 30f),
            floatArrayOf(50f, 60f, 70f),
            floatArrayOf(30f, 40f, 50f)
        )
        // ch0 sorted: [10,30,50] → middle = 30
        // ch1 sorted: [20,40,60] → middle = 40
        // ch2 sorted: [30,50,70] → middle = 50
        val dest = FloatArray(3)
        invokeLabMedian(labs, dest)

        assertThat(dest[0]).isWithin(1e-4f).of(30f)
        assertThat(dest[1]).isWithin(1e-4f).of(40f)
        assertThat(dest[2]).isWithin(1e-4f).of(50f)
    }

    // ─── E4b: ScanOrchestrator.SCAN_ORDER consistency ───────────────────────

    @Test
    fun `ScanOrchestrator SCAN_ORDER contains exactly 6 faces`() {
        assertThat(ScanOrchestrator.SCAN_ORDER.size).isEqualTo(6)
    }

    @Test
    fun `ScanOrchestrator SCAN_ORDER contains all six expected faces`() {
        val expected = setOf(Face.U, Face.F, Face.R, Face.B, Face.L, Face.D)
        assertThat(ScanOrchestrator.SCAN_ORDER.toSet()).isEqualTo(expected)
    }
}
