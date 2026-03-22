package com.xmelon.rubik_solver.solver

import com.xmelon.rubik_solver.model.Move

sealed class SolveResult {
    data class Success(
        val moves: List<Move>,
        val moveCount: Int
    ) : SolveResult()

    data class Error(
        val reason: String
    ) : SolveResult()
}
