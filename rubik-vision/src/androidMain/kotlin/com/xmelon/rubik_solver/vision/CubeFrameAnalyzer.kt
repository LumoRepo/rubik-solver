package com.xmelon.rubik_solver.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xmelon.rubik_solver.model.CubeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Analyzes live camera frames to extract 9 facelet colors.
 *
 * Pipeline:
 *   1. Extract per-tile LAB median (2×2-stride pixel sampling).
 *   2. Temporal median smoothing (ring buffer of 8 LAB values per tile).
 *   3. Classify each tile using ColorDetector (Bayesian Gaussian in CIELAB).
 *   4. Detect center stability via confidence gate + consecutive frame count.
 *
 * No white-balance step. Set [previewWidth] / [previewHeight] to the pixel
 * dimensions of the PreviewView.
 */
class CubeFrameAnalyzer : ImageAnalysis.Analyzer, FrameAnalyzer {

    companion object {
        private const val TAG = "CubeFrameAnalyzer"
        private const val TEMPORAL_BUFFER_SIZE = 8
        private const val CENTER_STABLE_FRAMES = 10
        private const val SCAN_FRAME_INTERVAL_MS = 1000L
        // Pixels with R+G+B <= this are near-black (lens dirt / border) and excluded from
        // tile LAB extraction and debug bitmap rendering.
        private const val DARK_PIXEL_THRESHOLD = 30
        // Fraction of cell size used as inset on each edge of a tile sampling region.
        // 15% avoids the cell border where sticker edges and surface curvature cause
        // colour blending with adjacent tiles or the cube frame.
        private const val TILE_INSET_FRACTION = 0.15f
    }

    /** Per-session color detector. Owned here so each scan session gets isolated state. */
    val colorDetector = ColorDetector()

    /** Pixel size of the PreviewView — set from the View's onLayoutChange. */
    @Volatile var previewWidth: Int = 0
    @Volatile var previewHeight: Int = 0

    /** Expected center color for the current face. Set by AppViewModel. */
    private val _expectedCenterColor = AtomicReference<CubeColor?>(null)
    override var expectedCenterColor: CubeColor?
        get() = _expectedCenterColor.get()
        set(value) { _expectedCenterColor.set(value) }

    // ---- StateFlows ----
    private val _detectedColors = MutableStateFlow<List<CubeColor>>(emptyList())
    val detectedColors = _detectedColors.asStateFlow()

    /** LAB-derived sRGB per tile. */
    private val _detectedWbRgbs = MutableStateFlow<IntArray>(IntArray(0))
    val detectedWbRgbs = _detectedWbRgbs.asStateFlow()
    /** Alias for [detectedWbRgbs] — kept for call-site compatibility. */
    val detectedRgbs get() = detectedWbRgbs

    /** Per-tile confidence in [0,1]. < 0.15 = uncertain. */
    private val _confidence = MutableStateFlow<FloatArray>(FloatArray(0))
    val confidence = _confidence.asStateFlow()

    @Volatile var debugMode: Boolean = false
    private val _debugBitmap = MutableStateFlow<Bitmap?>(null)
    val debugBitmap = _debugBitmap.asStateFlow()

    // ---- Temporal LAB ring buffers ----
    private val tileLabRingBuffers: Array<ArrayDeque<FloatArray>> = Array(9) { ArrayDeque() }

    // Reusable pixel buffer — avoids allocating a new IntArray for every tile every frame
    private var pixelBuffer = IntArray(0)

    // Scratch buffer for per-channel LAB sort.
    // Tiles can have hundreds of sampled pixels — starts at 512, grows on demand.
    private var labSortBuf = FloatArray(512)

    // Pre-allocated per-frame buffers — avoids heap allocations inside extractGridColors
    private val rawLab: Array<FloatArray> = Array(9) { FloatArray(3) }
    private val smoothedLab: Array<FloatArray> = Array(9) { FloatArray(3) }
    private val frameColors: ArrayList<CubeColor> = ArrayList(9)
    private val frameConfidences: FloatArray = FloatArray(9)
    private val frameRgbs: IntArray = IntArray(9)

    // Scratch buffers for extractTileLab — avoids ArrayList and per-pixel FloatArray allocs
    private val tileSumBuf = FloatArray(3)  // running LAB sum across sampled pixels

    // Rotation matrix cache
    private var cachedRotationDegrees = Int.MIN_VALUE
    private var cachedRotationMatrix = Matrix()

    // Center stability
    private val consecutiveCenterInRange = java.util.concurrent.atomic.AtomicInteger(0)
    private val _centerStable = MutableStateFlow(false)
    val centerStable = _centerStable.asStateFlow()

    // Stability-transition tracking (single-thread executor — no @Volatile needed for lastScanFrameMs)
    // Written from camera executor thread (analyze) and main thread (resetTemporalBuffers);
    // @Volatile required for cross-thread visibility.
    @Volatile private var wasStable: Boolean = false
    // Only written from camera executor thread; no @Volatile needed.
    private var lastScanFrameMs: Long = 0L

    @Volatile private var lastLoggedColors: List<CubeColor> = emptyList()

    /**
     * Clears temporal LAB buffers and resets center stability.
     * Call when the user switches to a new face.
     */
    override fun resetTemporalBuffers() {
        tileLabRingBuffers.forEach { it.clear() }
        lastLoggedColors = emptyList()
        consecutiveCenterInRange.set(0)
        _centerStable.value = false
        wasStable = false
        lastScanFrameMs = 0L
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = try {
                image.toBitmap()
            } catch (e: Exception) {
                Log.e(TAG, "toBitmap() failed", e)
                return
            }
            val degrees = image.imageInfo.rotationDegrees
            val rotatedBitmap = if (degrees != 0) {
                if (degrees != cachedRotationDegrees) {
                    cachedRotationMatrix = Matrix().apply { postRotate(degrees.toFloat()) }
                    cachedRotationDegrees = degrees
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, cachedRotationMatrix, true)
                    .also { bitmap.recycle() }
            } else {
                bitmap
            }
            try {
                val colors = extractGridColors(rotatedBitmap)
                _detectedColors.value = colors
            } catch (e: Exception) {
                Log.e(TAG, "extractGridColors() failed", e)
                _detectedColors.value = emptyList()
                _detectedWbRgbs.value = IntArray(0)
                _confidence.value = FloatArray(0)
            } finally {
                rotatedBitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    private fun visibleRegion(camW: Int, camH: Int): IntArray {
        val pW = previewWidth; val pH = previewHeight
        if (pW <= 0 || pH <= 0) return intArrayOf(0, 0, camW, camH)
        val camAspect = camW.toFloat() / camH
        val prevAspect = pW.toFloat() / pH
        return if (camAspect > prevAspect) {
            val visW = (camH * prevAspect).toInt()
            intArrayOf((camW - visW) / 2, 0, visW, camH)
        } else {
            val visH = (camW / prevAspect).toInt()
            intArrayOf(0, (camH - visH) / 2, camW, visH)
        }
    }

    private fun extractGridColors(bitmap: Bitmap): List<CubeColor> {
        val region = visibleRegion(bitmap.width, bitmap.height)
        val rx = region[0]; val ry = region[1]; val rw = region[2]; val rh = region[3]
        val boxSize = (minOf(rw, rh) * 0.66f).toInt()
        val startX = rx + (rw - boxSize) / 2
        val startY = ry + (rh - boxSize) / 2
        val cellSize = boxSize / 3
        val inset = (cellSize * TILE_INSET_FRACTION).toInt().coerceAtLeast(2)

        // 1. Extract raw LAB per tile (write into pre-allocated rawLab entries)
        for (row in 0..2) {
            for (col in 0..2) {
                val x0 = startX + col * cellSize + inset
                val y0 = startY + row * cellSize + inset
                val x1 = startX + (col + 1) * cellSize - inset
                val y1 = startY + (row + 1) * cellSize - inset
                extractTileLab(bitmap, x0, y0, x1, y1, rawLab[row * 3 + col])
            }
        }

        // 2. Temporal median smoothing in LAB space
        for (i in 0..8) {
            val buf = tileLabRingBuffers[i]
            buf.addLast(rawLab[i].copyOf())
            if (buf.size > TEMPORAL_BUFFER_SIZE) buf.removeFirst()
            labMedian(buf, smoothedLab[i])
        }

        // 3. Classify + build output arrays (reuse pre-allocated frameColors/frameConfidences/frameRgbs)
        frameColors.clear()
        for (i in 0..8) {
            val (color, conf) = colorDetector.classify(smoothedLab[i])
            frameColors.add(color)
            frameConfidences[i] = conf
            frameRgbs[i] = LabConverter.labToSRgb(smoothedLab[i])
        }

        // 4. Center stability: center tile's top-ranked color must equal the expected color,
        // sustained for N consecutive frames.
        // Using rank (classify) rather than an absolute NLL threshold so the check works
        // across cameras whose color rendering differs from the tuned priors — a tile that
        // consistently ranks #1 as the expected color IS that color, regardless of the raw NLL.
        val expected = expectedCenterColor
        val centerMatch = expected != null
                && colorDetector.classify(smoothedLab[4]).first == expected
        val newCount = if (centerMatch)
            consecutiveCenterInRange.updateAndGet { minOf(it + 1, CENTER_STABLE_FRAMES) }
        else {
            consecutiveCenterInRange.set(0)
            0
        }
        val nowStable = newCount >= CENTER_STABLE_FRAMES
        if (nowStable != _centerStable.value) _centerStable.value = nowStable

        // 5. Emit StateFlows
        // Copy required: frameRgbs is a reused pre-allocated array, so the same reference is
        // always emitted. MutableStateFlow uses reference equality for arrays, meaning it would
        // never re-emit after the first frame. The copy ensures a new reference each frame so
        // Compose sees updates and recomputes overlay colors with fresh data.
        _detectedWbRgbs.value = frameRgbs.copyOf()
        _confidence.value = frameConfidences.copyOf()

        // 6. Stability-transition events + SCAN_FRAME throttle
        if (nowStable && !wasStable) {
            wasStable = true
            val cL = smoothedLab[4]
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                val tileStr = frameColors.mapIndexed { i, c ->
                    "%s:%.2f".format(c.name.first(), frameConfidences[i])
                }.joinToString(" ")
                Log.i(TAG, "CENTER_STABLE face=${expected?.name} " +
                    "center=[%.1f,%.1f,%.1f] color=${frameColors[4].name} conf=%.2f tiles=[$tileStr]"
                        .format(cL[0], cL[1], cL[2], frameConfidences[4]))
            } else {
                Log.i(TAG, "CENTER_STABLE face=${expected?.name} " +
                    "center=[%.1f,%.1f,%.1f] color=${frameColors[4].name} conf=%.2f"
                        .format(cL[0], cL[1], cL[2], frameConfidences[4]))
            }
            Log.i(TAG, "MODEL_DUMP ${colorDetector.modelDumpStr()}")
        } else if (!nowStable && wasStable) {
            wasStable = false
            Log.i(TAG, "CENTER_LOST face=${expected?.name}")
        } else if (!nowStable) {
            val now = System.currentTimeMillis()
            if (now - lastScanFrameMs >= SCAN_FRAME_INTERVAL_MS) {
                lastScanFrameMs = now
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    val cL = smoothedLab[4]
                    val allColors = frameColors.map { it.name.first() }.joinToString("")
                    Log.d(TAG, "SCAN_FRAME face=${expected?.name} " +
                        "center=[%.1f,%.1f,%.1f] ${frameColors[4].name}:%.2f all=[$allColors]"
                            .format(cL[0], cL[1], cL[2], frameConfidences[4]))
                }
            }
        }

        // Legacy change-detection log (kept for compatibility)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            if (frameColors != lastLoggedColors) {
                lastLoggedColors = frameColors.toList()
                Log.d(TAG, "SCAN_DETECT exp=${expected?.name} center=${frameColors.getOrNull(4)?.name}" +
                    " conf=%.2f stable=$nowStable".format(frameConfidences[4]) +
                    " ${frameColors.map { it.label }}")
            }
        }

        // 7. Debug bitmap
        _debugBitmap.value = if (debugMode)
            buildDebugBitmap(bitmap, startX, startY, boxSize, cellSize, inset,
                frameColors, frameConfidences, smoothedLab)
        else null

        return frameColors.toList()
    }

    /**
     * Extracts a single tile's representative LAB value as the mean
     * of all 2×2-stride-sampled pixels in the tile's inset region.
     * Near-black pixels (likely lens dirt or border) are excluded.
     * Fallback: center pixel if fewer than 3 pixels survive the filter.
     * Result is written into [dest].
     */
    private fun extractTileLab(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, dest: FloatArray) {
        val w = (x1 - x0).coerceAtLeast(1)
        val h = (y1 - y0).coerceAtLeast(1)
        val needed = w * h
        if (pixelBuffer.size < needed) pixelBuffer = IntArray(needed)
        bitmap.getPixels(pixelBuffer, 0, w, x0, y0, w, h)
        // Running-sum approach — no ArrayList or per-pixel FloatArray allocation.
        // sRgbToLab() still allocates a FloatArray; see LabConverter for a future dest overload.
        tileSumBuf[0] = 0f; tileSumBuf[1] = 0f; tileSumBuf[2] = 0f
        var count = 0
        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                val p = pixelBuffer[row * w + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                if (r + g + b > DARK_PIXEL_THRESHOLD) {
                    val lab = LabConverter.sRgbToLab(p)
                    tileSumBuf[0] += lab[0]; tileSumBuf[1] += lab[1]; tileSumBuf[2] += lab[2]
                    count++
                }
            }
        }
        if (count < 3) {
            // Fallback to center pixel
            val cx = w / 2; val cy = h / 2
            val fallback = LabConverter.sRgbToLab(pixelBuffer[cy * w + cx])
            dest[0] = fallback[0]; dest[1] = fallback[1]; dest[2] = fallback[2]
            return
        }
        dest[0] = tileSumBuf[0] / count
        dest[1] = tileSumBuf[1] / count
        dest[2] = tileSumBuf[2] / count
    }

    /** Per-channel median of a list of LAB triplets. Result written into [dest]. Buffer grows on demand. */
    private fun labMedian(labs: List<FloatArray>, dest: FloatArray) {
        val n = labs.size
        if (labSortBuf.size < n) labSortBuf = FloatArray(n)
        for (ch in 0..2) {
            for (i in 0 until n) labSortBuf[i] = labs[i][ch]
            labSortBuf.sort(0, n)
            dest[ch] = if (n % 2 == 1) {
                labSortBuf[n / 2]
            } else {
                (labSortBuf[n / 2 - 1] + labSortBuf[n / 2]) / 2f
            }
        }
    }

    private fun buildDebugBitmap(
        src: Bitmap, startX: Int, startY: Int,
        boxSize: Int, cellSize: Int, inset: Int,
        colors: List<CubeColor>,
        confidences: FloatArray,
        smoothedLab: Array<FloatArray>
    ): Bitmap {
        val gridPixels = IntArray(boxSize * boxSize)
        src.getPixels(gridPixels, 0, boxSize, startX, startY, boxSize, boxSize)
        val outPixels = IntArray(boxSize * boxSize)

        for (tileRow in 0..2) {
            for (tileCol in 0..2) {
                val lx0 = tileCol * cellSize + inset
                val ly0 = tileRow * cellSize + inset
                val lx1 = (tileCol + 1) * cellSize - inset
                val ly1 = (tileRow + 1) * cellSize - inset
                if (lx1 <= lx0 || ly1 <= ly0) continue
                for (py in ly0 until ly1 step 2) {
                    for (px in lx0 until lx1 step 2) {
                        val p = gridPixels[py * boxSize + px]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8)  and 0xFF
                        val b =  p         and 0xFF
                        if (r + g + b > DARK_PIXEL_THRESHOLD) {
                            outPixels[py * boxSize + px] = (0xFF shl 24) or (p and 0x00FFFFFF)
                        }
                    }
                }
            }
        }

        val bmp = Bitmap.createBitmap(boxSize, boxSize, Bitmap.Config.ARGB_8888)
        bmp.setPixels(outPixels, 0, boxSize, 0, 0, boxSize, boxSize)

        val cvs = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        val smallTextSize = cellSize * 0.18f
        val labelTextSize = cellSize * 0.22f

        for (tileRow in 0..2) {
            for (tileCol in 0..2) {
                val idx   = tileRow * 3 + tileCol
                val conf  = confidences.getOrElse(idx) { 0f }
                val lab   = smoothedLab.getOrElse(idx) { FloatArray(3) }
                val color = colors.getOrNull(idx)
                val cx    = (tileCol * cellSize + (tileCol + 1) * cellSize) / 2f
                val cy    = (tileRow * cellSize + (tileRow + 1) * cellSize) / 2f

                // Confidence-coded border
                val borderArgb = when {
                    conf >= 0.20f -> 0xFF4CAF50.toInt()  // green
                    conf >= 0.10f -> 0xFFFFC107.toInt()  // yellow
                    else          -> 0xFFF44336.toInt()  // red
                }
                borderPaint.color = borderArgb
                cvs.drawRect(
                    (tileCol * cellSize + 2).toFloat(), (tileRow * cellSize + 2).toFloat(),
                    ((tileCol + 1) * cellSize - 2).toFloat(), ((tileRow + 1) * cellSize - 2).toFloat(),
                    borderPaint
                )

                // LAB lines
                paint.textSize = smallTextSize
                val labLine1 = "L:%.0f a:%.0f".format(lab[0], lab[1])
                val labLine2 = "b:%.0f".format(lab[2])
                val ty1 = cy - smallTextSize * 1.6f
                val ty2 = cy - smallTextSize * 0.3f
                for (stroke in listOf(true, false)) {
                    if (stroke) { paint.style = Paint.Style.STROKE; paint.strokeWidth = smallTextSize * 0.15f; paint.color = android.graphics.Color.BLACK }
                    else        { paint.style = Paint.Style.FILL;   paint.color = android.graphics.Color.WHITE }
                    cvs.drawText(labLine1, cx, ty1, paint)
                    cvs.drawText(labLine2, cx, ty2, paint)
                }

                // Color name + confidence %
                paint.textSize = labelTextSize
                val label = "${color?.name?.take(3) ?: "?"} ${(conf * 100).toInt()}%"
                val ty3   = cy + labelTextSize * 1.4f
                for (stroke in listOf(true, false)) {
                    if (stroke) { paint.style = Paint.Style.STROKE; paint.strokeWidth = labelTextSize * 0.15f; paint.color = android.graphics.Color.BLACK }
                    else        { paint.style = Paint.Style.FILL;   paint.color = borderArgb }
                    cvs.drawText(label, cx, ty3, paint)
                }
            }
        }
        return bmp
    }
}
