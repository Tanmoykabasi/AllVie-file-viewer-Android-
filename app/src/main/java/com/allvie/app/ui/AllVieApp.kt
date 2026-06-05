package com.allvie.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.allvie.app.domain.model.FileItem
import com.allvie.app.ui.screen.bookmarks.BookmarksScreen
import com.allvie.app.ui.screen.files.FilesScreen
import com.allvie.app.ui.screen.recents.RecentsScreen
import com.allvie.app.ui.screen.settings.SettingsScreen
import com.allvie.app.ui.screen.viewer.ViewerScreen
import com.allvie.app.ui.theme.AppBackgroundBrush
import com.allvie.app.ui.theme.allViePanelColor

private enum class AppTab(val route: String, val title: String, val icon: ImageVector) {
    FILES("files", "Files", Icons.Rounded.Description),
    RECENTS("recents", "Recents", Icons.Rounded.History),
    BOOKMARKS("bookmarks", "Bookmarks", Icons.Rounded.Bookmark),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings)
}

private object ViewerDestination {
    const val baseRoute = "viewer"
    const val route = "$baseRoute?uri={uri}&category={category}&name={name}&mime={mime}"
    val arguments = listOf(
        navArgument("uri") { type = NavType.StringType },
        navArgument("category") { type = NavType.StringType },
        navArgument("name") { type = NavType.StringType },
        navArgument("mime") { type = NavType.StringType }
    )

    fun createRoute(file: FileItem): String {
        return "$baseRoute?uri=${Uri.encode(file.uriString)}&category=${file.category.name}&name=${Uri.encode(file.displayName)}&mime=${Uri.encode(file.mimeType)}"
    }
}

@Composable
fun AllVieApp(
    hasStorageAccess: Boolean,
    onRequestStorageAccess: () -> Unit,
    externalOpenFile: FileItem? = null,
    onExternalOpenFileConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val route = navBackStackEntry?.destination?.route.orEmpty()
    val isViewer = route.startsWith(ViewerDestination.baseRoute)
    val selectedTab = AppTab.entries.firstOrNull { route.startsWith(it.route) } ?: AppTab.FILES

    LaunchedEffect(externalOpenFile?.uriString, externalOpenFile?.displayName, externalOpenFile?.mimeType) {
        val targetFile = externalOpenFile ?: return@LaunchedEffect
        if (targetFile.category.supportsInlinePreview) {
            navController.navigate(ViewerDestination.createRoute(targetFile)) {
                launchSingleTop = true
            }
        } else {
            Toast.makeText(context, "This file type is not supported in AllVie.", Toast.LENGTH_SHORT).show()
        }
        onExternalOpenFileConsumed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!isViewer) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = allViePanelColor(alphaLight = 0.72f, alphaDark = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 2.dp,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AllVie",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = selectedTab.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (!isViewer) {
                    val bottomPanelColor = allViePanelColor(alphaLight = 0.82f, alphaDark = 0.96f)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = bottomPanelColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bottomPanelColor)
                        ) {
                            NavigationBar(
                                modifier = Modifier.height(66.dp),
                                containerColor = bottomPanelColor,
                                tonalElevation = 0.dp
                            ) {
                                AppTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selectedTab == tab,
                                        onClick = {
                                            navController.navigate(tab.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.title,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.title,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = AppTab.FILES.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(AppTab.FILES.route) {
                    FilesScreen(
                        hasStorageAccess = hasStorageAccess,
                        onRequestStorageAccess = onRequestStorageAccess,
                        onOpenFile = { file -> openFile(navController, file, context) }
                    )
                }
                composable(AppTab.RECENTS.route) {
                    RecentsScreen(
                        onOpenFile = { file -> openFile(navController, file, context) }
                    )
                }
                composable(AppTab.BOOKMARKS.route) {
                    BookmarksScreen(
                        onOpenFile = { file -> openFile(navController, file, context) }
                    )
                }
                composable(AppTab.SETTINGS.route) {
                    SettingsScreen()
                }
                composable(
                    route = ViewerDestination.route,
                    arguments = ViewerDestination.arguments
                ) {
                    ViewerScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private fun openFile(navController: NavHostController, file: FileItem, context: android.content.Context) {
    if (file.category.supportsInlinePreview) {
        navController.navigate(ViewerDestination.createRoute(file))
        return
    }

    Toast.makeText(context, "This file type is not supported in AllVie.", Toast.LENGTH_SHORT).show()
}
