package com.xmelon.rubik_solver.solver

import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Move
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CubeSolverTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            CubeSolver.initialize()
        }
    }

    @Test
    fun `solving an already solved cube returns empty moves`() {
        val result = runBlocking { CubeSolver.solve(CubeState.solved()) }
        assertTrue(result is SolveResult.Success)
        assertTrue(result.moves.isEmpty())
        assertEquals(0, result.moveCount)
    }

    @Test
    fun `solving a cube scrambled with one move returns the inverse move`() {
        val scrambled = CubeState.solved().applyMove(Move.U)
        val result = runBlocking { CubeSolver.solve(scrambled) }
        assertTrue(result is SolveResult.Success)
        assertEquals(listOf(Move.U_PRIME), result.moves)
    }

    @Test
    fun `solving a moderately scrambled cube works correctly`() {
        val scrambleAlg = Move.parseSequence("R U R' U' R' F R2 U' R' U' R U R' F'")
        val scrambled = CubeState.solved().applyMoves(scrambleAlg)
        val result = runBlocking { CubeSolver.solve(scrambled) }
        assertTrue(result is SolveResult.Success)
        val finalState = scrambled.applyMoves(result.moves)
        assertTrue(finalState.isSolved())
        assertTrue(result.moveCount <= 21)
    }

    private fun invokeParseError(input: String): SolveResult.Error {
        val method: Method = CubeSolver::class.java
            .getDeclaredMethod("parseError", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(CubeSolver, input) as SolveResult.Error
    }

    @Test fun `parseError returns raw Error 2 code string`() {
        assertEquals("Error 2", invokeParseError("Error 2").reason)
    }

    @Test fun `parseError returns raw Error 5 code string`() {
        assertEquals("Error 5", invokeParseError("Error 5").reason)
    }

    @Test fun `parseError returns raw Error 8 code string`() {
        assertEquals("Error 8", invokeParseError("Error 8").reason)
    }

    @Test fun `parseError passes through unknown error strings verbatim`() {
        assertEquals("Error 99", invokeParseError("Error 99").reason)
    }

    @Test fun `parseError result reason contains no English description`() {
        for (code in 2..8) {
            assertEquals("Error $code", invokeParseError("Error $code").reason)
        }
    }

    @Test
    fun `solver returns proper validation errors for physically impossible states`() {
        val f = CubeState.solved().facelets
        val tmp = f[8]; f[8] = f[9]; f[9] = f[20]; f[20] = tmp
        val invalidState = CubeState.fromFacelets(f)
        assertFalse(invalidState.isSolved())
        val result = runBlocking { CubeSolver.solve(invalidState) }
        assertTrue(result is SolveResult.Error)
        assertTrue(result.reason.isNotEmpty())
    }
}
