package com.xmelon.rubik_solver.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubeValidatorTest {

    @Test
    fun `solved cube passes validation`() {
        val result = CubeValidator.validate(CubeState.solved())
        assertTrue(result is CubeValidator.ValidationResult.Valid)
    }

    @Test
    fun `cube with wrong color counts fails validation`() {
        val f = CubeState.solved().facelets
        f[0] = CubeColor.YELLOW.ordinal
        val state = CubeState.fromFacelets(f)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
    }

    @Test
    fun `cube with non-distinct centers fails validation`() {
        val f = CubeState.solved().facelets
        f[31] = CubeColor.WHITE.ordinal
        f[0]  = CubeColor.YELLOW.ordinal
        val state = CubeState.fromFacelets(f)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
    }

    @Test
    fun `cube with duplicate corner colors fails validation`() {
        val fFixed = CubeState.solved().facelets
        fFixed[20] = CubeColor.WHITE.ordinal
        fFixed[1]  = CubeColor.RED.ordinal
        val state = CubeState.fromFacelets(fFixed)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
        assertTrue(result.reason.contains("Corner"))
    }

    @Test
    fun `cube with duplicate edge colors fails validation`() {
        val f = CubeState.solved().facelets
        f[19] = CubeColor.WHITE.ordinal
        f[5]  = CubeColor.RED.ordinal
        val state = CubeState.fromFacelets(f)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
        assertTrue(result.reason.contains("Edge"))
    }

    @Test
    fun `cube with unscanned sentinel -1 returns Invalid`() {
        val f = CubeState.solved().facelets
        f[0] = -1
        val state = CubeState.fromFacelets(f)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
        assertTrue(result.reason.contains("scanned"))
    }

    @Test
    fun `cube with multiple unscanned sentinels returns Invalid`() {
        val f = IntArray(54) { -1 }
        val state = CubeState.fromFacelets(f)
        val result = CubeValidator.validate(state)
        assertTrue(result is CubeValidator.ValidationResult.Invalid)
        assertTrue(result.reason.contains("scanned"))
    }
}
