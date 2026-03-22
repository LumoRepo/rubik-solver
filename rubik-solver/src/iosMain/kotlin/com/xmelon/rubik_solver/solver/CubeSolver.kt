package com.xmelon.rubik_solver.solver

import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.cinterop.toKString
import min2phase.min2phase_c_init
import min2phase.min2phase_c_solve

actual object CubeSolver {
    actual fun initialize() {
        min2phase_c_init()
    }

    actual suspend fun solve(state: CubeState, timeoutMs: Long): SolveResult =
        withContext(Dispatchers.Default) {
            initialize()
            if (state.isSolved()) return@withContext SolveResult.Success(emptyList(), 0)
            withTimeoutOrNull(timeoutMs) {
                try {
                    val result = min2phase_c_solve(
                        state.toFaceletString(),
                        21,   // maxDepth
                        1000, // probeMax
                        0,    // probeMin
                        0     // verbose
                    )?.toKString() ?: return@withTimeoutOrNull SolveResult.Error("Null from solver")
                    if (result.startsWith("Error")) SolveResult.Error(result)
                    else {
                        val moves = Move.parseSequence(result)
                        SolveResult.Success(moves, moves.size)
                    }
                } catch (e: Exception) {
                    SolveResult.Error("Solver error: ${e.message}")
                }
            } ?: SolveResult.Error("Solver timed out after ${timeoutMs / 1000}s")
        }
}
