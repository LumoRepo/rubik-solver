package com.xmelon.rubik_solver.model

/**
 * All 18 moves of a 3×3 Rubik's Cube in standard notation.
 *
 * - Single letter = 90° clockwise (e.g. `U`)
 * - Prime (') = 90° counter-clockwise (e.g. `U_PRIME`)
 * - 2 = 180° (e.g. `U2`)
 *
 * Each move records which [Face] it rotates and how many quarter-turns (1, 2, or 3).
 */
enum class Move(
    val face: Face,
    /** Number of 90° clockwise quarter-turns (1=CW, 2=180°, 3=CCW). */
    val quarterTurns: Int,
    val notation: String
) {
    U(Face.U, 1, "U"),
    U_PRIME(Face.U, 3, "U'"),
    U2(Face.U, 2, "U2"),

    D(Face.D, 1, "D"),
    D_PRIME(Face.D, 3, "D'"),
    D2(Face.D, 2, "D2"),

    R(Face.R, 1, "R"),
    R_PRIME(Face.R, 3, "R'"),
    R2(Face.R, 2, "R2"),

    L(Face.L, 1, "L"),
    L_PRIME(Face.L, 3, "L'"),
    L2(Face.L, 2, "L2"),

    F(Face.F, 1, "F"),
    F_PRIME(Face.F, 3, "F'"),
    F2(Face.F, 2, "F2"),

    B(Face.B, 1, "B"),
    B_PRIME(Face.B, 3, "B'"),
    B2(Face.B, 2, "B2");

    companion object {
        /**
         * Parse standard cube notation like "U", "R'", "F2".
         * @throws IllegalArgumentException if the notation is not recognized.
         */
        fun parse(notation: String): Move {
            val trimmed = notation.trim()
            return entries.firstOrNull { it.notation == trimmed }
                ?: throw IllegalArgumentException("Unknown move notation: '$trimmed'")
        }

        /**
         * Parse a space-separated sequence of moves.
         * Example: "R U R' U' R' F R2 U' R' U' R U R' F'"
         */
        fun parseSequence(sequence: String): List<Move> {
            if (sequence.isBlank()) return emptyList()
            return sequence.trim().split("\\s+".toRegex()).map { parse(it) }
        }
    }
}
