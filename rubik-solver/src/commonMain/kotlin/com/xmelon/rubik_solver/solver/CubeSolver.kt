package com.xmelon.rubik_solver.solver

import com.xmelon.rubik_solver.model.CubeState

expect object CubeSolver {
    fun initialize()
    suspend fun solve(state: CubeState, timeoutMs: Long = 5_000L): SolveResult
}
