package com.xmelon.rubik_solver.ui

import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xmelon.rubik_solver.generated.resources.Res
import com.xmelon.rubik_solver.generated.resources.camera_retry
import com.xmelon.rubik_solver.vision.CubeFrameAnalyzer
import com.xmelon.rubik_solver.vision.FrameAnalyzer
import java.util.concurrent.Executors
import org.jetbrains.compose.resources.stringResource

/**
 * Camera preview layer for scan mode.
 * Manages its own executor, error/retry state, and optional debug overlay.
 * The debug toggle button is rendered here since it belongs to the camera layer.
 */
@Composable
actual fun CameraPreviewLayer(
    analyzer: FrameAnalyzer,
    debugMode: Boolean,
    onDebugToggle: () -> Unit,
    modifier: Modifier
) {
    val cubeAnalyzer = analyzer as CubeFrameAnalyzer
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }
    var cameraError    by remember { mutableStateOf<String?>(null) }
    var cameraRetryKey by remember { mutableIntStateOf(0) }
    val debugBitmap    by cubeAnalyzer.debugBitmap.collectAsState()

    Box(modifier) {
        key(cameraRetryKey) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        setupCamera(ctx, lifecycleOwner, pv, cubeAnalyzer, cameraExecutor) { cameraError = it }
                        pv.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                            cubeAnalyzer.previewWidth  = r - l
                            cubeAnalyzer.previewHeight = b - t
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        cameraError?.let { err ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(err, color = MaterialTheme.colorScheme.onError, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { cameraError = null; cameraRetryKey++ },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)
                ) { Text(stringResource(Res.string.camera_retry)) }
            }
        }

        if (debugMode) {
            debugBitmap?.let { bmp ->
                val imgBitmap = bmp.asImageBitmap()
                Canvas(Modifier.fillMaxSize()) {
                    // Draw the debug grid centred, occupying the same 66% square
                    // region as the tile extraction area in the camera frame.
                    val short = minOf(size.width, size.height)
                    val px    = (short * 0.66f).toInt()
                    val left  = ((size.width  - px) / 2).toInt()
                    val top   = ((size.height - px) / 2).toInt()
                    drawImage(imgBitmap, IntOffset.Zero, IntSize(bmp.width, bmp.height),
                        IntOffset(left, top), IntSize(px, px))
                }
            }
        }

    }
}

private fun setupCamera(
    context:       Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView:   PreviewView,
    analyzer:      CubeFrameAnalyzer,
    executor:      java.util.concurrent.ExecutorService,
    onError:       (String) -> Unit
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview  = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { it.setAnalyzer(executor, analyzer) }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: Exception) {
            android.util.Log.e("CameraPreviewLayer", "Camera setup failed", e)
            onError("Camera error: ${e.message ?: e::class.simpleName ?: ""}")
        }
    }, ContextCompat.getMainExecutor(context))
}
