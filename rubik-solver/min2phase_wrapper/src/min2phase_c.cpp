#include "../include/min2phase_c.h"
#include "../include/min2phase/min2phase.h"
#include <string>

// Single static buffer — not thread-safe. Caller must copy immediately.
static std::string g_last_result;

extern "C" {

void min2phase_c_init(void) {
    min2phase::init();
}

const char* min2phase_c_solve(
    const char* facelets,
    int         max_depth,
    int         probe_max,
    int         probe_min,
    int         verbose
) {
    g_last_result = min2phase::solve(
        std::string(facelets),
        static_cast<int8_t>(max_depth),
        static_cast<int32_t>(probe_max),
        static_cast<int32_t>(probe_min),
        static_cast<int8_t>(verbose)
    );
    return g_last_result.c_str();
}

} // extern "C"
