package com.xmelon.rubik_solver.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.generated.resources.Res
import com.xmelon.rubik_solver.generated.resources.scan_button_align
import com.xmelon.rubik_solver.generated.resources.scan_button_confirm
import com.xmelon.rubik_solver.generated.resources.scan_button_edit
import com.xmelon.rubik_solver.generated.resources.solve_button_repeat
import com.xmelon.rubik_solver.generated.resources.solve_button_rescan
import com.xmelon.rubik_solver.generated.resources.solve_button_restart
import com.xmelon.rubik_solver.model.Face
import org.jetbrains.compose.resources.stringResource

/**
 * Footer button row for scan mode: back / confirm-or-edit / forward.
 * All action logic is provided as callbacks from [MainScreenContent].
 */
@Composable
internal fun RowScope.ScanFooter(
    currentFace:            Face,
    isViewingScanned:       Boolean,
    isAwaitingAlignment:    Boolean,
    faceScanned:            Boolean,
    effectiveLiveColorsSize: Int,
    onBack:    () -> Unit,
    onEdit:    () -> Unit,
    onConfirm: () -> Unit,
    onForward: () -> Unit
) {
    OutlinedButton(
        onClick  = onBack,
        modifier = Modifier.height(56.dp).weight(1f),
        shape    = RoundedCornerShape(16.dp)
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }

    Button(
        onClick = { if (isViewingScanned) onEdit() else onConfirm() },
        modifier = Modifier.height(56.dp).weight(2f),
        shape    = RoundedCornerShape(16.dp),
        enabled  = isViewingScanned || (!isAwaitingAlignment && effectiveLiveColorsSize == 9)
    ) {
        Text(
            stringResource(when {
                isViewingScanned    -> Res.string.scan_button_edit
                isAwaitingAlignment -> Res.string.scan_button_align
                else                -> Res.string.scan_button_confirm
            }),
            style = MaterialTheme.typography.titleMedium
        )
    }

    OutlinedButton(
        onClick         = onForward,
        modifier        = Modifier.height(56.dp).weight(1f),
        shape           = RoundedCornerShape(16.dp),
        enabled         = faceScanned,
        contentPadding  = PaddingValues(0.dp)
    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
}

/**
 * Footer button row for solve mode: prev / repeat-or-rescan-or-restart / next.
 * All action logic is provided as callbacks from [MainScreenContent].
 */
@Composable
internal fun RowScope.SolveFooter(
    currentStep: Int,
    isSolved:    Boolean,
    canGoNext:   Boolean,
    onPrev:      () -> Unit,
    onMiddle:    () -> Unit,
    onNext:      () -> Unit
) {
    OutlinedButton(
        onClick  = onPrev,
        modifier = Modifier.height(56.dp).weight(1f),
        shape    = RoundedCornerShape(16.dp)
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }

    val midLabel = when {
        isSolved         -> stringResource(Res.string.solve_button_restart)
        currentStep == 0 -> stringResource(Res.string.solve_button_rescan)
        else             -> stringResource(Res.string.solve_button_repeat)
    }
    Button(
        onClick  = onMiddle,
        modifier = Modifier.height(56.dp).weight(2f),
        shape    = RoundedCornerShape(16.dp)
    ) { Text(midLabel, style = MaterialTheme.typography.titleMedium) }

    OutlinedButton(
        onClick        = onNext,
        modifier       = Modifier.height(56.dp).weight(1f),
        shape          = RoundedCornerShape(16.dp),
        enabled        = canGoNext,
        contentPadding = PaddingValues(0.dp)
    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
}
