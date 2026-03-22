package com.xmelon.rubik_solver.model

/**
 * Immutable representation of a 3×3 Rubik's Cube state.
 *
 * The cube is stored as a 54-element [IntArray] of facelet colors (ordinals of [CubeColor]).
 * Facelets are indexed by face in the order U, R, F, D, L, B; within each face the
 * 9 facelets are ordered row-by-row, left-to-right, top-to-bottom:
 *
 * ```
 *          ┌──┬──┬──┐
 *          │ 0│ 1│ 2│
 *          ├──┼──┼──┤
 *    U     │ 3│ 4│ 5│
 *          ├──┼──┼──┤
 *          │ 6│ 7│ 8│
 *     ┌────┼──┼──┼──┼────┬────┐
 *     │36 …│18│19│20│  9…│45… │
 *  L  │  … │  │  │  │ R  │ B  │
 *     │ …44│24│25│26│ …17│ …53│
 *     └────┼──┼──┼──┼────┴────┘
 *          │27│28│29│
 *    D     │30│31│32│
 *          │33│34│35│
 *          └──┴──┴──┘
 * ```
 *
 * Face offsets: U=0, R=9, F=18, D=27, L=36, B=45.
 */
class CubeState private constructor(
    /** The 54 facelet colors as [CubeColor] ordinals. */
    private val _facelets: IntArray
) {
    /** Returns a safe copy of the facelets. */
    val facelets: IntArray get() = _facelets.copyOf()

    init {
        require(_facelets.size == FACELET_COUNT) {
            "CubeState requires exactly $FACELET_COUNT facelets, got ${_facelets.size}"
        }
    }

    // ─── Queries ─────────────────────────────────────────────────────────

    /** Returns the color at [index] (0..53) or null if unscanned. */
    fun colorAt(index: Int): CubeColor? {
        val f = _facelets[index]
        return if (f in CubeColor.entries.indices) CubeColor.entries[f] else null
    }

    /** Returns the color at position [pos] (0..8) on the given [face]. */
    fun colorAt(face: Face, pos: Int): CubeColor? = colorAt(face.offset + pos)

    /** Returns the center color of the given [face] (position 4). */
    fun centerColor(face: Face): CubeColor? = colorAt(face.offset + 4)

    /** True if every face has a uniform color. */
    fun isSolved(): Boolean = Face.entries.all { face ->
        val center = _facelets[face.offset + 4]
        (0 until 9).all { _facelets[face.offset + it] == center }
    }

    // ─── Move Application ────────────────────────────────────────────────

    /**
     * Returns a new [CubeState] with the given [move] applied.
     * This is a pure function — the receiver is not modified.
     */
    fun applyMove(move: Move): CubeState {
        var state = this
        repeat(move.quarterTurns) {
            state = state.applySingleCW(move.face)
        }
        return state
    }

    /**
     * Returns a new [CubeState] with all [moves] applied sequentially.
     */
    fun applyMoves(moves: List<Move>): CubeState =
        moves.fold(this) { state, move -> state.applyMove(move) }

    /**
     * Apply a single 90° clockwise rotation of [face].
     */
    private fun applySingleCW(face: Face): CubeState {
        val next = _facelets.copyOf()

        // 1) Rotate the 9 facelets on the face itself (CW 90°)
        val o = face.offset
        next[o + 0] = _facelets[o + 6]
        next[o + 1] = _facelets[o + 3]
        next[o + 2] = _facelets[o + 0]
        next[o + 3] = _facelets[o + 7]
        // next[o + 4] stays (center)
        next[o + 5] = _facelets[o + 1]
        next[o + 6] = _facelets[o + 8]
        next[o + 7] = _facelets[o + 5]
        next[o + 8] = _facelets[o + 2]

        // 2) Cycle the 12 adjacent edge facelets
        val adj = ADJACENT_CYCLES[face.index]
        for (i in 0 until 3) {
            next[adj[0][i]] = _facelets[adj[3][i]]
            next[adj[1][i]] = _facelets[adj[0][i]]
            next[adj[2][i]] = _facelets[adj[1][i]]
            next[adj[3][i]] = _facelets[adj[2][i]]
        }

        return CubeState(next)
    }

    // ─── Serialization ───────────────────────────────────────────────────

    /**
     * Converts to the "facelet string" format used by Kociemba / min2phase.
     * 54 characters: U face first, then R, F, D, L, B.
     * Each character is U/R/F/D/L/B indicating which *face* that facelet's
     * color belongs to (based on center colors).
     */
    fun toFaceletString(): String {
        // Map each color to its face label based on center assignments
        val colorToFace = Face.entries.mapNotNull { face ->
            val color = centerColor(face)
            if (color != null) color to face else null
        }.toMap()
        
        return buildString(FACELET_COUNT) {
            for (i in 0 until FACELET_COUNT) {
                val f = _facelets[i]
                if (f !in CubeColor.entries.indices) {
                    append("-")
                } else {
                    val color = CubeColor.entries[f]
                    val face = colorToFace[color]
                        ?: throw IllegalStateException("Color $color not found on any center")
                    append(face.name)
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CubeState && _facelets.contentEquals(other._facelets)

    override fun hashCode(): Int = _facelets.contentHashCode()

    override fun toString(): String {
        return buildString {
            for (face in Face.entries) {
                append("${face.name}: ")
                for (i in 0 until 9) {
                    val f = _facelets[face.offset + i]
                    append(if (f in CubeColor.entries.indices) CubeColor.entries[f].label else '-')
                    if (i == 2 || i == 5) append("|")
                }
                append("\n")
            }
        }
    }

    // ─── Companion / Factory ─────────────────────────────────────────────

    companion object {
        const val FACELET_COUNT = 54
        const val FACELETS_PER_FACE = 9

        /**
         * Creates a solved cube with the standard color scheme:
         * U=White, R=Blue, F=Red, D=Yellow, L=Green, B=Orange
         */
        fun solved(): CubeState {
            val f = IntArray(FACELET_COUNT)
            val faceColors = intArrayOf(
                CubeColor.WHITE.ordinal,   // U
                CubeColor.BLUE.ordinal,    // R
                CubeColor.RED.ordinal,     // F
                CubeColor.YELLOW.ordinal,  // D
                CubeColor.GREEN.ordinal,   // L
                CubeColor.ORANGE.ordinal   // B
            )
            for (face in 0 until Face.COUNT) {
                for (pos in 0 until FACELETS_PER_FACE) {
                    f[face * FACELETS_PER_FACE + pos] = faceColors[face]
                }
            }
            return CubeState(f)
        }

        /**
         * Creates a [CubeState] from a 54-character string of color labels
         * (W, Y, R, O, B, G).
         */
        fun fromColorString(colors: String): CubeState {
            require(colors.length == FACELET_COUNT) {
                "Color string must be $FACELET_COUNT characters, got ${colors.length}"
            }
            val f = IntArray(FACELET_COUNT) { CubeColor.fromLabel(colors[it]).ordinal }
            return CubeState(f)
        }

        /**
         * Creates a [CubeState] from a raw [IntArray] of color ordinals.
         * The array is copied to ensure immutability.
         */
        fun fromFacelets(facelets: IntArray): CubeState {
            require(facelets.size == FACELET_COUNT) {
                "Expected $FACELET_COUNT facelets, got ${facelets.size}"
            }
            val colorCount = CubeColor.entries.size
            require(facelets.all { it in -1 until colorCount }) {
                "Facelet values must be in -1..${colorCount - 1}"
            }
            return CubeState(facelets.copyOf())
        }

        private val ADJACENT_CYCLES: Array<Array<IntArray>> = arrayOf(
            // U (0): R top -> F top -> L top -> B top
            arrayOf(
                intArrayOf( 9, 10, 11), intArrayOf(18, 19, 20),
                intArrayOf(36, 37, 38), intArrayOf(45, 46, 47)
            ),
            // R (1): F right -> U right -> B left rev -> D right
            arrayOf(
                intArrayOf(20, 23, 26), intArrayOf( 2,  5,  8),
                intArrayOf(51, 48, 45), intArrayOf(29, 32, 35)
            ),
            // F (2): U bottom -> R left -> D top rev -> L right rev
            arrayOf(
                intArrayOf( 6,  7,  8), intArrayOf( 9, 12, 15),
                intArrayOf(29, 28, 27), intArrayOf(44, 41, 38)
            ),
            // D (3): F bottom -> R bottom -> B bottom -> L bottom
            arrayOf(
                intArrayOf(24, 25, 26), intArrayOf(15, 16, 17),
                intArrayOf(51, 52, 53), intArrayOf(42, 43, 44)
            ),
            // L (4): F left -> D left -> B right rev -> U left
            arrayOf(
                intArrayOf(18, 21, 24), intArrayOf(27, 30, 33),
                intArrayOf(53, 50, 47), intArrayOf( 0,  3,  6)
            ),
            // B (5): U top rev -> L left -> D bottom -> R right rev
            arrayOf(
                intArrayOf( 2,  1,  0), intArrayOf(36, 39, 42),
                intArrayOf(33, 34, 35), intArrayOf(17, 14, 11)
            )
        )

        /**
         * Applies [moves] to a 54-element [IntArray] using the same permutation as
         * [CubeState.applyMoves]. Values may be arbitrary (e.g. packed RGB colors);
         * only positions are permuted, not values.
         */
        fun permute(source: IntArray, moves: List<Move>): IntArray {
            require(source.size == FACELET_COUNT)
            var arr = source.copyOf()
            for (move in moves) {
                repeat(move.quarterTurns) {
                    val next = arr.copyOf()
                    val o = move.face.offset
                    next[o+0]=arr[o+6]; next[o+1]=arr[o+3]; next[o+2]=arr[o+0]
                    next[o+3]=arr[o+7]; next[o+5]=arr[o+1]
                    next[o+6]=arr[o+8]; next[o+7]=arr[o+5]; next[o+8]=arr[o+2]
                    val adj = ADJACENT_CYCLES[move.face.index]
                    for (i in 0 until 3) {
                        next[adj[0][i]]=arr[adj[3][i]]; next[adj[1][i]]=arr[adj[0][i]]
                        next[adj[2][i]]=arr[adj[1][i]]; next[adj[3][i]]=arr[adj[2][i]]
                    }
                    arr = next
                }
            }
            return arr
        }
    }
}
