package com.xmelon.rubik_solver

import androidx.compose.runtime.Composable

@Composable
expect fun rememberCameraPermissionState(onResult: (Boolean) -> Unit): () -> Unit

@Composable
expect fun checkCameraPermission(): Boolean
