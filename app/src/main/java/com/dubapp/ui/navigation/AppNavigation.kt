package com.dubapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dubapp.viewmodel.DubViewModel
import com.dubapp.ui.screens.HomeScreen
import com.dubapp.ui.screens.Step1Screen
import com.dubapp.ui.screens.Step2Screen
import com.dubapp.ui.screens.Step3Screen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Step1 : Screen("step1")
    data object Step2 : Screen("step2")
    data object Step3 : Screen("step3")
}

@Composable
fun AppNavigation(viewModel: DubViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onStartClick = {
                    navController.navigate(Screen.Step1.route)
                }
            )
        }

        composable(Screen.Step1.route) {
            Step1Screen(
                viewModel = viewModel,
                onNext = {
                    navController.navigate(Screen.Step2.route)
                }
            )
        }

        composable(Screen.Step2.route) {
            Step2Screen(
                viewModel = viewModel,
                onNext = {
                    navController.navigate(Screen.Step3.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Step3.route) {
            Step3Screen(
                viewModel = viewModel,
                onBack = {
                    navController.popBackStack()
                },
                onFinish = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
