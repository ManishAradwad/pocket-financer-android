package com.pocketfinancer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketfinancer.ui.navigation.Screen
import com.pocketfinancer.ui.settings.SettingsScreen
import com.pocketfinancer.ui.theme.M3_OnSecondaryContainer
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_SecondaryContainer
import com.pocketfinancer.ui.theme.M3_SurfaceContainer
import com.pocketfinancer.ui.theme.PocketFinancerTheme

@Composable
fun PocketFinancerRoot() {
    PocketFinancerTheme {
        val navController = rememberNavController()

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = M3_SurfaceContainer
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    Screen.tabs.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.label
                                )
                            },
                            label = {
                                Text(
                                    text = screen.label,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = M3_OnSecondaryContainer,
                                selectedTextColor = M3_OnSurface,
                                unselectedIconColor = M3_OnSurfaceVariant,
                                unselectedTextColor = M3_OnSurfaceVariant,
                                indicatorColor = M3_SecondaryContainer
                            )
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
                composable(Screen.Home.route) { PlaceholderScreen("Home Dashboard") }
                composable(Screen.Transactions.route) { PlaceholderScreen("Transactions") }
                composable(Screen.Insights.route) { PlaceholderScreen("Insights") }
                composable(Screen.Settings.route) { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = M3_OnSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
    }
}
