package com.xmelon.rubik_solver.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.xmelon.rubik_solver.model.CubeColor
import com.xmelon.rubik_solver.model.CubeState
import com.xmelon.rubik_solver.model.Face
import com.xmelon.rubik_solver.model.Move
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.PI

/** Simple mutable holder for non-state data shared between Canvas draw and pointerInput. */
private class FrameRef<T>(var value: T)

/**
 * 3D isometric representation of a Rubik's cube with rotation animations.
 */
@Composable
fun Cube3DView(
    modifier: Modifier = Modifier,
    cubeState: CubeState,
    animatingMove: Move? = null,
    moveProgress: Float = 0f,
    baseRotateX: Float = -35f,
    baseRotateY: Float = -45f,
    baseRotateZ: Float = 0f,
    dragEnabled: Boolean = true,
    dragResetKey: Int = 0,
    pitchOverride: Float? = null,
    yawOverride: Float? = null,
    colorPalette: Map<CubeColor, Int> = emptyMap(),
    // --- Scanner Features ---
    faceOverrides: Map<Face, Array<Color?>> = emptyMap(),
    highlightCenterFace: Face? = null,
    overrideIndicatorIndices: Map<Face, Set<Int>> = emptyMap(),
    onTileTap: ((Face, Int) -> Unit)? = null
) {
    // Accumulated world-space drag rotation. Each gesture pre-multiplies a world-axis
    // delta so horizontal drag always orbits the screen-vertical axis (world Y) and
    // vertical drag always orbits the screen-horizontal axis (world X), regardless of
    // the cube's current orientation.
    var dragMatrix by remember { mutableStateOf(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)) }

    val pitch = (PI * (pitchOverride ?: baseRotateX) / 180.0).toFloat()
    val yaw   = (PI * (yawOverride   ?: baseRotateY) / 180.0).toFloat()
    val roll  = (PI * baseRotateZ / 180.0).toFloat()
    val baseMatrix = createRotationMatrix(pitch, yaw, roll)

    // When the intro-animation overrides kick in, reset accumulated drag so the two
    // don't compound.
    LaunchedEffect(pitchOverride, yawOverride, dragResetKey) {
        if (pitchOverride != null || yawOverride != null) {
            dragMatrix = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        }
    }

    val cameraMatrix = multiplyMatrices(dragMatrix, baseMatrix)

    // Build the 27 cubies only when cubeState changes (e.g. each solve step).
    val cubies = remember(cubeState) { buildCubies(cubeState) }

    // Holds the last-rendered polys for hit-testing in pointerInput.
    // Both Canvas draw and pointerInput run on the main thread, so no synchronization needed.
    val currentFramePolys = remember { FrameRef(emptyList<RenderPoly>()) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(dragEnabled, onTileTap) {
                fun cross(o: Offset, a: Offset, b: Offset) =
                    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
                fun pointInQuad(pt: Offset, q: List<Offset>): Boolean {
                    val d0 = cross(q[0], q[1], pt); val d1 = cross(q[1], q[2], pt)
                    val d2 = cross(q[2], q[3], pt); val d3 = cross(q[3], q[0], pt)
                    return (d0 > 0 && d1 > 0 && d2 > 0 && d3 > 0) ||
                           (d0 < 0 && d1 < 0 && d2 < 0 && d3 < 0)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var prevPos = downPos
                    var isDrag = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (!isDrag && dragEnabled && onTileTap != null) {
                                // Hit-test front-to-back
                                val tappedPoly = currentFramePolys.value.reversed().firstOrNull { poly ->
                                    pointInQuad(downPos, poly.pts)
                                }
                                if (tappedPoly != null && tappedPoly.face != null) {
                                    onTileTap.invoke(tappedPoly.face, tappedPoly.idx)
                                }
                            }
                            break
                        }

                        if (!isDrag && (change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                            isDrag = true
                        }

                        if (isDrag && dragEnabled) {
                            change.consume()
                            val dragAmount = change.position - prevPos
                            val rotRadius = minOf(size.width, size.height) * RenderingConstants.OVERLAY_SCALE_FACTOR *
                                    (RenderingConstants.FOCAL_LENGTH / RenderingConstants.CAMERA_DISTANCE)
                            val pitchRad = dragAmount.y / rotRadius
                            val afterPitch = multiplyMatrices(createRotationMatrix(pitchRad, 0f, 0f), dragMatrix)
                            val yawRad = dragAmount.x / rotRadius
                            dragMatrix = multiplyMatrices(createRotationMatrix(0f, yawRad, 0f), afterPitch)
                        }
                        prevPos = change.position
                    }
                }
            }
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val scale = size.minDimension * RenderingConstants.OVERLAY_SCALE_FACTOR
        val focalLength = RenderingConstants.FOCAL_LENGTH
        val cameraDistance = RenderingConstants.CAMERA_DISTANCE

        // Identify which cubies are animating
        val animatingFace = animatingMove?.face
        val sliceAxis = when (animatingFace) {
            Face.R, Face.L -> 0 // X
            Face.U, Face.D -> 1 // Y
            Face.F, Face.B -> 2 // Z
            null -> -1
        }
        val sliceSign = when (animatingFace) {
            Face.R, Face.U, Face.F -> 1f
            Face.L, Face.D, Face.B -> -1f
            null -> 0f
        }

        // Calculate animation angle.
        // quarterTurns: 1=CW(90°), 2=half(180°), 3=CCW(-90°).
        // Convert to visual turns so prime moves go the short way.
        val moveAngleBase = animatingMove?.let {
            val visualTurns = when (it.quarterTurns) {
                3 -> -1f   // CCW = -90° (not +270°)
                else -> it.quarterTurns.toFloat()
            }
            // Standard Right-Hand-Rule: +Rotation is CCW.
            // CW Moves need -Rotation signs.
            val sign = when (it.face) {
                Face.R, Face.U, Face.F -> -1f
                Face.L, Face.D, Face.B -> 1f
            }
            visualTurns * 90f * sign
        } ?: 0f

        val currentAnimAngle = (PI * (moveAngleBase * moveProgress) / 180.0).toFloat()
        val animMatrix = createRotationAxisMatrix(sliceAxis, currentAnimAngle)

        // Transform and project cubies to polygons
        val renderPolys = projectCubies(
            cubies = cubies,
            cameraMatrix = cameraMatrix,
            animMatrix = animMatrix,
            sliceAxis = sliceAxis,
            sliceSign = sliceSign,
            centerX = centerX,
            centerY = centerY,
            scale = scale,
            colorPalette = colorPalette,
            faceOverrides = faceOverrides
        )

        // Render polygons
        val sortedPolys = renderPolys.sortedBy { it.z }
        currentFramePolys.value = sortedPolys
        drawCubePolys(sortedPolys, highlightCenterFace, overrideIndicatorIndices)
    }
}

private fun projectCubies(
    cubies: List<Cubie>,
    cameraMatrix: FloatArray,
    animMatrix: FloatArray,
    sliceAxis: Int,
    sliceSign: Float,
    centerX: Float,
    centerY: Float,
    scale: Float,
    colorPalette: Map<CubeColor, Int>,
    faceOverrides: Map<Face, Array<Color?>>
): List<RenderPoly> {
    val renderPolys = mutableListOf<RenderPoly>()
    val focalLength = RenderingConstants.FOCAL_LENGTH
    val cameraDistance = RenderingConstants.CAMERA_DISTANCE

    for (c in cubies) {
        val isAnimating = sliceAxis != -1 && when (sliceAxis) {
            0 -> c.gridX == sliceSign.toInt()
            1 -> c.gridY == sliceSign.toInt()
            2 -> c.gridZ == sliceSign.toInt()
            else -> false
        }

        for (i in 0 until 6) {
            val cf = c.faces[i]
            if (cf.face == null) continue

            val pRaw = cf.vertices.map { v ->
                var p = v
                if (isAnimating) p = multiplyMatrixVector(animMatrix, p)
                multiplyMatrixVector(cameraMatrix, p)
            }

            val projectedPoints = pRaw.map { v ->
                val factor = focalLength / (cameraDistance + v.z)
                Offset(centerX + v.x * scale * factor, centerY - v.y * scale * factor)
            }

            // Backface culling
            val v0 = projectedPoints[0]; val v1 = projectedPoints[1]; val v2 = projectedPoints[2]
            val cp = (v1.x - v0.x) * (v2.y - v1.y) - (v1.y - v0.y) * (v2.x - v1.x)

            if (cp < 0) {
                val avgZ = pRaw.map { it.z }.average().toFloat()
                val baseColor = cf.color?.let { toComposeColor(it, colorPalette) }
                val overrideColor = cf.face.let { faceOverrides[it]?.getOrNull(cf.idx) }

                renderPolys.add(RenderPoly(
                    z = avgZ,
                    pts = projectedPoints,
                    color = overrideColor ?: baseColor,
                    face = cf.face,
                    idx = cf.idx
                ))
            }
        }
    }
    return renderPolys
}

private fun DrawScope.drawCubePolys(
    polys: List<RenderPoly>,
    highlightCenterFace: Face?,
    overrideIndicatorIndices: Map<Face, Set<Int>>
) {
    val gridPadding = RenderingConstants.GRID_PADDING
    for (poly in polys) {
        val p = poly.pts
        // Border path: full quad
        val borderPath = Path().apply {
            moveTo(p[0].x, p[0].y); lineTo(p[1].x, p[1].y)
            lineTo(p[2].x, p[2].y); lineTo(p[3].x, p[3].y); close()
        }
        // Padded fill path
        val cx = (p[0].x + p[1].x + p[2].x + p[3].x) / 4f
        val cy = (p[0].y + p[1].y + p[2].y + p[3].y) / 4f
        fun pad(o: Offset) = Offset(cx + (o.x - cx) * gridPadding, cy + (o.y - cy) * gridPadding)
        val paddedPath = Path().apply {
            val q = p.map { pad(it) }
            moveTo(q[0].x, q[0].y); lineTo(q[1].x, q[1].y)
            lineTo(q[2].x, q[2].y); lineTo(q[3].x, q[3].y); close()
        }
        val isOverridden = poly.face != null && overrideIndicatorIndices[poly.face]?.contains(poly.idx) == true

        drawPath(path = borderPath, color = Color.White.copy(alpha = 0.6f), style = Stroke(width = 2.5f))
        if (poly.color != null) drawPath(path = paddedPath, color = poly.color)

        // Highlight Center Tile
        if (poly.idx == 4 && poly.face == highlightCenterFace) {
            drawPath(paddedPath, Color.White.copy(alpha = 0.7f), style = Stroke(width = 8f))
        } else if (poly.idx == 4) {
            drawPath(paddedPath, Color.White.copy(alpha = 0.4f), style = Stroke(width = 4f))
        }

        // Highlight Overridden Tiles
        if (isOverridden) {
            drawPath(paddedPath, Color.Yellow.copy(alpha = 0.9f), style = Stroke(width = 3f))
        }
    }
}

private class CubieFace(val vertices: List<Vec3>, val color: CubeColor?, val face: Face?, val idx: Int)

private class Cubie(val pos: Vec3, val gridX: Int, val gridY: Int, val gridZ: Int, val faces: Array<CubieFace>)

private class RenderPoly(val z: Float, val pts: List<Offset>, val color: Color?, val face: Face?, val idx: Int)

private fun toComposeColor(color: CubeColor?, palette: Map<CubeColor, Int> = emptyMap()) = when (color) {
    null -> Color.DarkGray
    else -> palette[color]?.let { Color(it) } ?: color.toComposeColor()
}

/**
 * Coordinate system:
 * X right (R)
 * Y up (U)
 * Z towards viewer (F)
 */
private fun buildCubies(cubeState: CubeState): List<Cubie> {
    val cubies = mutableListOf<Cubie>()
    val unit = 2f / 3f
    val s = unit / 2f

    for (x in -1..1) {
        for (y in -1..1) {
            for (z in -1..1) {
                // If inner core, skip
                if (x == 0 && y == 0 && z == 0) continue

                val cx = x * unit
                val cy = y * unit
                val cz = z * unit

                val fR = if (x == 1) cubeState.colorAt(Face.R, getRIndex(y, z)) else null
                val fL = if (x == -1) cubeState.colorAt(Face.L, getLIndex(y, z)) else null
                val fU = if (y == 1) cubeState.colorAt(Face.U, getUIndex(x, z)) else null
                val fD = if (y == -1) cubeState.colorAt(Face.D, getDIndex(x, z)) else null
                val fF = if (z == 1) cubeState.colorAt(Face.F, getFIndex(x, y)) else null
                val fB = if (z == -1) cubeState.colorAt(Face.B, getBIndex(x, y)) else null

                val iR = getRIndex(y, z); val iL = getLIndex(y, z)
                val iU = getUIndex(x, z); val iD = getDIndex(x, z)
                val iF = getFIndex(x, y); val iB = getBIndex(x, y)

                val faces = arrayOf(
                    // R: x=+s
                    CubieFace(listOf(Vec3(cx + s, cy - s, cz + s), Vec3(cx + s, cy - s, cz - s), Vec3(cx + s, cy + s, cz - s), Vec3(cx + s, cy + s, cz + s)), fR, if (x == 1) Face.R else null, iR),
                    // L: x=-s
                    CubieFace(listOf(Vec3(cx - s, cy - s, cz - s), Vec3(cx - s, cy - s, cz + s), Vec3(cx - s, cy + s, cz + s), Vec3(cx - s, cy + s, cz - s)), fL, if (x == -1) Face.L else null, iL),
                    // U: y=+s
                    CubieFace(listOf(Vec3(cx - s, cy + s, cz + s), Vec3(cx + s, cy + s, cz + s), Vec3(cx + s, cy + s, cz - s), Vec3(cx - s, cy + s, cz - s)), fU, if (y == 1) Face.U else null, iU),
                    // D: y=-s
                    CubieFace(listOf(Vec3(cx - s, cy - s, cz - s), Vec3(cx + s, cy - s, cz - s), Vec3(cx + s, cy - s, cz + s), Vec3(cx - s, cy - s, cz + s)), fD, if (y == -1) Face.D else null, iD),
                    // F: z=+s
                    CubieFace(listOf(Vec3(cx - s, cy - s, cz + s), Vec3(cx + s, cy - s, cz + s), Vec3(cx + s, cy + s, cz + s), Vec3(cx - s, cy + s, cz + s)), fF, if (z == 1) Face.F else null, iF),
                    // B: z=-s
                    CubieFace(listOf(Vec3(cx + s, cy - s, cz - s), Vec3(cx - s, cy - s, cz - s), Vec3(cx - s, cy + s, cz - s), Vec3(cx + s, cy + s, cz - s)), fB, if (z == -1) Face.B else null, iB)
                )

                cubies.add(Cubie(Vec3(cx, cy, cz), x, y, z, faces))
            }
        }
    }
    return cubies
}

// Map 3D grid coords to 0..8 face indices
// Z=+1 is F, Z=-1 is B. X=+1 is R, X=-1 is L. Y=+1 is U, Y=-1 is D.
private fun getFIndex(x: Int, y: Int) = (1 - y) * 3 + (x + 1)
private fun getBIndex(x: Int, y: Int) = (1 - y) * 3 + (1 - x)
private fun getRIndex(y: Int, z: Int) = (1 - y) * 3 + (1 - z)
private fun getLIndex(y: Int, z: Int) = (1 - y) * 3 + (z + 1)
private fun getUIndex(x: Int, z: Int) = (z + 1) * 3 + (x + 1)
private fun getDIndex(x: Int, z: Int) = (1 - z) * 3 + (x + 1)
