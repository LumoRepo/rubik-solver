package com.xmelon.rubik_solver.ui

internal object RenderingConstants {
    /** Perspective focal length — controls apparent field-of-view. */
    const val FOCAL_LENGTH = 15.0f

    /** Camera distance along Z for Linear Inverted Perspective. */
    const val CAMERA_DISTANCE = 14.0f

    /** Cube scale relative to the canvas minDimension. */
    const val OVERLAY_SCALE_FACTOR = 0.33f

    /** Tile padding: 0 = full cell, 1 = invisible. Controls gap between tiles. */
    const val GRID_PADDING = 0.88f
}
