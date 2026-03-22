package com.xmelon.rubik_solver.model

/**
 * The six faces of a Rubik's Cube, using standard notation.
 *
 * Each face has a fixed index (0-5) that determines its position in
 * the [CubeState] facelets array: face F occupies indices [F.offset .. F.offset+8].
 *
 * Standard color mapping (solved state):
 *   U=White, D=Yellow, F=Red, B=Orange, L=Green, R=Blue
 */
enum class Face(val index: Int) {
    /** Up (top) */
    U(0),
    /** Right */
    R(1),
    /** Front */
    F(2),
    /** Down (bottom) */
    D(3),
    /** Left */
    L(4),
    /** Back */
    B(5);

    /** Starting offset in the 54-element facelets array. */
    val offset: Int get() = index * 9

    companion object {
        /** Face count. */
        const val COUNT = 6

        fun fromIndex(i: Int): Face = entries[i]
    }
}
