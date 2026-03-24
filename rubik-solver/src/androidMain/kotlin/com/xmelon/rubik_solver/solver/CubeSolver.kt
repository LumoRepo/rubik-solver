package com.xmelon.rubik_solver.solver

import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Face
import com.xmelon.rubik_solver.model.Move
import cs.min2phase.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

actual object CubeSolver {

    private const val MAX_DEPTH = 21
    private const val PROBE_MAX = 1000L
    private const val PROBE_MIN = 0L

    @Synchronized
    actual fun initialize() {
        if (!Search.isInited()) {
            Search.init()
        }
    }

    actual suspend fun solve(state: CubeState, timeoutMs: Long): SolveResult =
        withContext(Dispatchers.Default) {
            initialize()

            if (state.isSolved()) {
                return@withContext SolveResult.Success(emptyList(), 0)
            }

            withTimeoutOrNull(timeoutMs) {
                try {
                    val faceletString = state.toFaceletString()
                    val search = Search()
                    val resultString = search.solution(
                        faceletString,
                        MAX_DEPTH,
                        PROBE_MAX,
                        PROBE_MIN,
                        0
                    )

                    if (resultString.startsWith("Error 1")) {
                        diagnoseColorCounts(state)
                    } else if (resultString.startsWith("Error")) {
                        parseError(resultString)
                    } else {
                        val moves = Move.parseSequence(resultString)
                        SolveResult.Success(moves, moves.size)
                    }
                } catch (e: Exception) {
                    SolveResult.Error(e.message ?: "Unexpected solver error")
                }
            } ?: SolveResult.Error("Error timeout")
        }

    private fun diagnoseColorCounts(state: CubeState): SolveResult.Error {
        val facelets = state.facelets
        val counts = IntArray(CubeColor.entries.size)
        for (v in facelets) counts[v]++

        val colorFace = Face.entries.associate { CubeColor.expectedCenter(it) to it.name }

        val parts = CubeColor.entries
            .filter { counts[it.ordinal] != 9 }
            .joinToString(", ") { c ->
                val faceName = colorFace[c]?.let { " ($it)" } ?: ""
                "${c.name.lowercase().replaceFirstChar { it.uppercase() }}$faceName: ${counts[c.ordinal]}/9"
            }
        return if (parts.isEmpty()) SolveResult.Error("Wrong facelet arrangement (Error 1)")
        else SolveResult.Error("Wrong facelet counts: $parts")
    }

    private fun parseError(errorResponse: String): SolveResult.Error =
        SolveResult.Error(errorResponse.trim())
}
