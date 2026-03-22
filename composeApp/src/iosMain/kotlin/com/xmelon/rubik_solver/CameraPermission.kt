package com.xmelon.rubik_solver

import androidx.compose.runtime.Composable
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType

@Composable
actual fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit = {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> onResult(granted) }
}

@Composable
actual fun checkCameraPermission(): Boolean =
    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
