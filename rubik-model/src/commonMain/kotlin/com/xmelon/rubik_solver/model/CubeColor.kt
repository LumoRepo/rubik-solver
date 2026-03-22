package com.xmelon.rubik_solver.model

/**
 * Represents the six colors found on a standard Rubik's Cube.
 * Each color corresponds to a specific center face in the solved state.
 */
enum class CubeColor(val label: Char) {
    WHITE('W'),
    YELLOW('Y'),
    RED('R'),
    ORANGE('O'),
    BLUE('B'),
    GREEN('G');

    companion object {
        fun fromLabel(c: Char): CubeColor =
            entries.firstOrNull { it.label == c.uppercaseChar() }
                ?: throw IllegalArgumentException("Unknown color label: $c")

        fun expectedCenter(face: Face): CubeColor = when (face) {
            Face.U -> WHITE
            Face.F -> RED
            Face.R -> BLUE
            Face.B -> ORANGE
            Face.L -> GREEN
            Face.D -> YELLOW
        }
    }
}
