// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.manager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zenpatch.manager.ui.screens.HomeScreen
import dev.zenpatch.manager.ui.screens.ModulesScreen
import dev.zenpatch.manager.ui.screens.PatchScreen
import dev.zenpatch.manager.ui.screens.SettingsScreen

/** Top-level navigation destinations. */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Patch : Screen("patch")
    data object Modules : Screen("modules")
    data object Settings : Screen("settings")
}

/**
 * Root navigation host for the ZenPatch Manager app.
 *
 * Uses a single [NavHost] with a bottom navigation bar. All screens are top-level
 * destinations to keep the back stack simple. The bottom bar is hidden when the
 * patch wizard is visible, since that screen has its own navigation controls.
 */
@Composable
fun ZenPatchNavHost() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // The patch wizard is a sub-screen reached via FAB; hide the bottom bar there.
    val showBottomBar = currentRoute != Screen.Patch.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Home.route,
                        onClick = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                            }
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Modules.route,
                        onClick = {
                            navController.navigate(Screen.Modules.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                        icon = { Icon(Icons.Default.Extension, contentDescription = "Modules") },
                        label = { Text("Modules") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Settings.route,
                        onClick = {
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(navController = navController)
            }
            composable(Screen.Patch.route) {
                PatchScreen(navController = navController)
            }
            composable(Screen.Modules.route) {
                ModulesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
