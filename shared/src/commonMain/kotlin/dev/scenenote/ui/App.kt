package dev.scenenote.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.scenenote.ui.home.HomeScreen
import dev.scenenote.ui.live.LiveScreen
import dev.scenenote.ui.settings.SettingsScreen
import dev.scenenote.ui.theme.SceneNoteTheme

object Routes {
    const val HOME = "home"
    const val LIVE = "live/{sceneId}"
    const val SETTINGS = "settings"
    fun live(sceneId: String) = "live/$sceneId"
}

@Composable
fun App() {
    SceneNoteTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(onOpenScene = { nav.navigate(Routes.live(it)) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.LIVE) { entry ->
                val sceneId = entry.savedStateHandle.get<String>("sceneId") ?: "live_talk"
                LiveScreen(sceneId = sceneId, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
