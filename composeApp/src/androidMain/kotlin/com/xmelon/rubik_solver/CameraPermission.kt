package com.xmelon.rubik_solver

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
actual fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(), onResult
    )
    return { launcher.launch(Manifest.permission.CAMERA) }
}

actual fun checkCameraPermission(): Boolean = true
