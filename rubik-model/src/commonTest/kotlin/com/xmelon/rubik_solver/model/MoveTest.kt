package com.xmelon.rubik_solver.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoveTest {

    @Test
    fun `parseSequence of empty string returns empty list`() {
        val result = Move.parseSequence("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSequence of whitespace-only string returns empty list`() {
        val result = Move.parseSequence("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSequence of three moves returns list of three`() {
        val result = Move.parseSequence("R U R'")
        assertEquals(3, result.size)
        assertEquals(Move.R, result[0])
        assertEquals(Move.U, result[1])
        assertEquals(Move.R_PRIME, result[2])
    }

    @Test
    fun `parseSequence handles leading and trailing whitespace`() {
        val result = Move.parseSequence("  F2 B  ")
        assertEquals(2, result.size)
        assertEquals(Move.F2, result[0])
        assertEquals(Move.B, result[1])
    }

    @Test
    fun `parseSequence of all 18 moves round-trips through notation`() {
        val notations = Move.entries.joinToString(" ") { it.notation }
        val result = Move.parseSequence(notations)
        assertEquals(Move.entries.toList(), result)
    }

    @Test
    fun `parse of unknown notation throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { Move.parse("X") }
    }
}
