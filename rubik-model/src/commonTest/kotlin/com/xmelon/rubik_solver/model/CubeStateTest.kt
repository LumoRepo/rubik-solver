package com.xmelon.rubik_solver.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubeStateTest {

    @Test
    fun `solved state creates correct colors and facelet string`() {
        val solved = CubeState.solved()
        assertTrue(solved.isSolved())

        val fString = solved.toFaceletString()
        // Format should be UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB
        assertEquals(
            "U".repeat(9) + "R".repeat(9) + "F".repeat(9) +
            "D".repeat(9) + "L".repeat(9) + "B".repeat(9),
            fString
        )
    }

    @Test
    fun `U move quarter turn applied to solved cube`() {
        val solved = CubeState.solved()
        val uMove = solved.applyMove(Move.U)

        assertFalse(uMove.isSolved())

        // U face should remain all White (U)
        for (i in 0 until 9) {
            assertEquals(CubeColor.WHITE, uMove.colorAt(Face.U, i))
        }

        // D face should remain all Yellow (D)
        for (i in 0 until 9) {
            assertEquals(CubeColor.YELLOW, uMove.colorAt(Face.D, i))
        }

        val u4 = solved
            .applyMove(Move.U)
            .applyMove(Move.U)
            .applyMove(Move.U)
            .applyMove(Move.U)

        assertTrue(u4.isSolved())
        assertEquals(solved, u4)
    }

    @Test
    fun `all moves cycle back to solved state 4 quarter turns`() {
        val solved = CubeState.solved()

        for (move in Move.entries.filter { it.quarterTurns == 1 }) {
            val m4 = solved
                .applyMove(move)
                .applyMove(move)
                .applyMove(move)
                .applyMove(move)

            assertTrue(m4.isSolved(), "Applying $move 4 times should yield a solved cube")
            assertEquals(solved, m4, "Applying $move 4 times should equal original solved state")
        }
    }

    @Test
    fun `half turns cycle back to solved state in 2 applications`() {
        val solved = CubeState.solved()

        for (move in Move.entries.filter { it.quarterTurns == 2 }) {
            val m2 = solved
                .applyMove(move)
                .applyMove(move)

            assertTrue(m2.isSolved())
            assertEquals(solved, m2)
        }
    }

    @Test
    fun `prime turns cycle back to solved state in 4 applications`() {
        val solved = CubeState.solved()

        for (move in Move.entries.filter { it.quarterTurns == 3 }) {
            val m4 = solved
                .applyMove(move)
                .applyMove(move)
                .applyMove(move)
                .applyMove(move)

            assertTrue(m4.isSolved())
            assertEquals(solved, m4)
        }
    }

    @Test
    fun `move and its prime inverse cancel each other`() {
        val solved = CubeState.solved()

        val tests = listOf(
            Pair(Move.U, Move.U_PRIME),
            Pair(Move.D, Move.D_PRIME),
            Pair(Move.R, Move.R_PRIME),
            Pair(Move.L, Move.L_PRIME),
            Pair(Move.F, Move.F_PRIME),
            Pair(Move.B, Move.B_PRIME)
        )

        for ((m, mPrime) in tests) {
            val state = solved.applyMove(m).applyMove(mPrime)
            assertTrue(state.isSolved())
            assertEquals(solved, state)
        }
    }

    @Test
    fun `basic color string parsing`() {
        val solvedStr = "W".repeat(9) + "B".repeat(9) + "R".repeat(9) +
                        "Y".repeat(9) + "G".repeat(9) + "O".repeat(9)
        val state = CubeState.fromColorString(solvedStr)
        assertTrue(state.isSolved())
    }

    @Test
    fun `D-face camera-to-kociemba index mapping is correct`() {
        val expected = intArrayOf(2, 5, 8, 1, 4, 7, 0, 3, 6)
        for (k in 0 until 9) {
            val mapped = (k % 3) * 3 + (2 - k / 3)
            assertEquals(expected[k], mapped, "D-face mapping for camera index $k")
        }
    }

    @Test
    fun `D-face center index maps to itself`() {
        val k = 4
        val mapped = (k % 3) * 3 + (2 - k / 3)
        assertEquals(4, mapped)
    }

    @Test
    fun `D-face mapping is a bijection on 0 to 8`() {
        val mapped = (0 until 9).map { k -> (k % 3) * 3 + (2 - k / 3) }
        assertEquals((0 until 9).toSet(), mapped.toSet())
    }
}
