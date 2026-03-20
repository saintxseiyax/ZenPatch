package dev.zenpatch.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zenpatch.manager.ui.screens.HomeScreen
import dev.zenpatch.manager.ui.screens.ModulesScreen
import dev.zenpatch.manager.ui.screens.PatchWizardScreen
import dev.zenpatch.manager.ui.screens.SettingsScreen
import dev.zenpatch.manager.ui.theme.ZenPatchTheme

/**
 * Main activity for ZenPatch Manager.
 * Hosts the Compose UI with bottom navigation and NavHost.
 * Supports Predictive Back gesture (Android 16+).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenPatchTheme {
                ZenPatchNavHost()
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector
) {
    object Home : Screen("home", R.string.nav_home, Icons.Filled.Home)
    object Modules : Screen("modules", R.string.nav_modules, Icons.Filled.Extension)
    object PatchWizard : Screen("patch_wizard", R.string.patch_wizard_title, Icons.Filled.Build)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)
}

private val bottomNavItems = listOf(
    Screen.Home,
    Screen.Modules,
    Screen.PatchWizard,
    Screen.Settings
)

@Composable
fun ZenPatchNavHost(
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            ZenPatchBottomBar(navController = navController)
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToPatchWizard = {
                        navController.navigate(Screen.PatchWizard.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Modules.route) {
                ModulesScreen()
            }
            composable(Screen.PatchWizard.route) {
                PatchWizardScreen(
                    onNavigateUp = { navController.navigateUp() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun ZenPatchBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                label = { Text(stringResource(screen.titleRes)) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
