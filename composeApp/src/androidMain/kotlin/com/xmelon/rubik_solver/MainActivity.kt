package com.xmelon.rubik_solver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmelon.rubik_solver.ui.HomeScreen
import com.xmelon.rubik_solver.ui.MainScreen
import com.xmelon.rubik_solver.ui.ScreenProgressBar

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AndroidApp(vm) }
    }
}

@Composable
fun AndroidApp(vm: AppViewModel) {
    MaterialTheme {
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                ScreenProgressBar(
                    progress = vm.overallProgress,
                    phase = vm.progressPhase,
                    scanningFace = vm.scanningFace,
                    currentStep = vm.solvingStep,
                    totalSteps = vm.solvingTotalSteps,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (vm.currentScreen) {
                        Screen.HOME -> {
                            HomeScreen(onScanClicked = {
                                vm.currentScreen = Screen.SCAN
                            })
                        }
                        Screen.SCAN -> {
                            BackHandler { vm.currentScreen = Screen.HOME }
                            MainScreen(
                                vm = vm,
                                onBack = { vm.currentScreen = Screen.HOME },
                                onDone = { vm.currentScreen = Screen.HOME }
                            )
                        }
                    }
                }
            }
        }
    }
}
