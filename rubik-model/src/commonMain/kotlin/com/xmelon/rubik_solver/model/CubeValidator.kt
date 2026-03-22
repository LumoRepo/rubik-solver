package com.xmelon.rubik_solver.model

/**
 * Validates a [CubeState] to ensure it's a physically possible Rubik's Cube configuration.
 */
object CubeValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(state: CubeState): ValidationResult {
        // Cache the array — state.facelets copies on every access.
        val f = state.facelets

        // Guard: reject any out-of-range facelet (e.g. -1 = unscanned sentinel).
        if (f.any { it !in 0 until CubeColor.entries.size }) {
            return ValidationResult.Invalid("Cube is not fully scanned")
        }

        // 1. Check facelet counts (exactly 9 of each color)
        val counts = IntArray(CubeColor.entries.size)
        for (v in f) counts[v]++
        for (i in counts.indices) {
            if (counts[i] != 9) {
                val color = CubeColor.entries[i]
                return ValidationResult.Invalid(
                    "Expected 9 facelets of color ${color.name}, but found ${counts[i]}"
                )
            }
        }

        // 2. Check centers — all 6 must be distinct colors.
        val centers = mutableSetOf<Int>()
        for (face in Face.entries) centers.add(f[face.offset + 4])
        if (centers.size != 6) {
            return ValidationResult.Invalid("Cube centers must be 6 distinct colors")
        }

        // 3. Corner validity — each of the 8 corners must show 3 different colors.
        //    A corner with two or more identical colors is physically impossible.
        //    Facelet index layout: U=0, R=9, F=18, D=27, L=36, B=45
        //    Each row is [u/d-facelet, side1-facelet, side2-facelet].
        val corners = arrayOf(
            intArrayOf( 8, 20,  9),  // UFR: U[8], F[2], R[0]
            intArrayOf( 6, 18, 38),  // UFL: U[6], F[0], L[2]
            intArrayOf( 2, 45, 11),  // UBR: U[2], B[0], R[2]
            intArrayOf( 0, 47, 36),  // UBL: U[0], B[2], L[0]
            intArrayOf(29, 26, 15),  // DFR: D[2], F[8], R[6]
            intArrayOf(27, 24, 44),  // DFL: D[0], F[6], L[8]
            intArrayOf(35, 51, 17),  // DBR: D[8], B[6], R[8]
            intArrayOf(33, 53, 42)   // DBL: D[6], B[8], L[6]
        )
        for (c in corners) {
            val c0 = f[c[0]]; val c1 = f[c[1]]; val c2 = f[c[2]]
            if (c0 == c1 || c1 == c2 || c0 == c2) {
                return ValidationResult.Invalid(
                    "Corner at facelets ${c.toList()} has duplicate colors — impossible position"
                )
            }
        }

        // 4. Edge validity — each of the 12 edges must show 2 different colors.
        val edges = arrayOf(
            intArrayOf( 7, 19),  // UF
            intArrayOf( 5, 10),  // UR
            intArrayOf( 1, 46),  // UB
            intArrayOf( 3, 37),  // UL
            intArrayOf(23, 12),  // FR
            intArrayOf(21, 41),  // FL
            intArrayOf(48, 14),  // BR
            intArrayOf(50, 39),  // BL
            intArrayOf(28, 25),  // DF
            intArrayOf(32, 16),  // DR
            intArrayOf(34, 52),  // DB
            intArrayOf(30, 43)   // DL
        )
        for (e in edges) {
            if (f[e[0]] == f[e[1]]) {
                return ValidationResult.Invalid(
                    "Edge at facelets ${e.toList()} has the same color on both sides — impossible position"
                )
            }
        }

        // 5. Full orientation/permutation parity is checked by min2phase internally.
        //    It returns specific error codes (Error 2–6) for each invariant violation,
        //    which CubeSolver.parseError maps to human-readable messages.

        return ValidationResult.Valid
    }
}
