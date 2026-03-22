#pragma once
#ifdef __cplusplus
extern "C" {
#endif

/** Initialize pruning tables. Call once before solve. ~1s on first run. */
void min2phase_c_init(void);

/**
 * Solve a Rubik's cube.
 * @param facelets 54-character facelet string (Kociemba notation: URFDLB)
 * @param max_depth max move count (20-31, use 21 for optimal-ish fast solve)
 * @param probe_max max probes (1000 is fast and reliable)
 * @param probe_min min phase-2 probes (0 for standard search)
 * @param verbose   flags: 0=standard, 1=APPEND_LENGTH, 2=USE_SEPARATOR
 * @return statically-allocated solution string (e.g. "U R2 F'") or "Error N".
 *         NOT thread-safe — copy the result immediately.
 */
const char* min2phase_c_solve(
    const char* facelets,
    int         max_depth,
    int         probe_max,
    int         probe_min,
    int         verbose
);

#ifdef __cplusplus
}
#endif
