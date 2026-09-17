package dev.scenenote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneTab
import dev.scenenote.core.designsystem.SceneTabBar
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.platform.DeepLinks
import dev.scenenote.ui.gallery.DesignSystemGallery
import dev.scenenote.ui.home.HomeTab
import dev.scenenote.ui.library.LibraryTab
import dev.scenenote.ui.live.LiveConversationScreen
import dev.scenenote.ui.live.LiveM4Screen
import dev.scenenote.ui.live.LiveTab
import dev.scenenote.ui.live.PlaceholderSessionScreen
import dev.scenenote.ui.models.ModelsScreen
import dev.scenenote.ui.onboarding.OnboardingScreen
import dev.scenenote.ui.selftest.AudioSelfTestScreen
import dev.scenenote.ui.settings.SettingsTab
import dev.scenenote.ui.theme.SceneNoteTheme

/**
 * 路由（07 篇 §7.10 深链统一 scenenote://）：
 * - main?tab=0..3       四 Tab 壳（场景 / 实时 / 资料库 / 设置）
 * - live/{sceneId}      全屏会话（按场景 liveModeId 分发到 M0 / M4 …）
 * - models / selftest / gallery / onboarding
 */
object Routes {
    const val MAIN = "main?tab={tab}"
    fun main(tab: Int = 0) = "main?tab=$tab"
    const val LIVE = "live/{sceneId}?autostart={autostart}&other={other}&my={my}&feed={feed}"
    fun live(sceneId: String, autostart: Boolean = false, other: String = "", my: String = "", feed: String = "") = "live/$sceneId?autostart=$autostart&other=$other&my=$my&feed=$feed"
    const val GALLERY = "gallery"
    const val ONBOARDING = "onboarding?page={page}"
    fun onboarding(page: Int = 0) = "onboarding?page=$page"
    const val MODELS = "models?install={install}"
    fun models(install: String = "") = "models?install=$install"
    const val SELFTEST = "selftest?autostart={autostart}&stop={stop}&bench={bench}&tts={tts}"
    fun selfTest(autostart: Boolean = false, stopAfterSec: Int = 0, bench: String = "", tts: Boolean = false) = "selftest?autostart=$autostart&stop=$stopAfterSec&bench=$bench&tts=$tts"
}

object Tabs { const val HOME = 0; const val LIVE = 1; const val LIBRARY = 2; const val SETTINGS = 3 }

@Composable
fun App() {
    SceneNoteTheme {
        val nav = rememberNavController()
        val link by DeepLinks.pending.collectAsState()
        LaunchedEffect(link) {
            val l = link ?: return@LaunchedEffect
            when (l.host) {
                "selftest" -> nav.navigate(Routes.selfTest(l.query["autostart"] == "1", l.query["stop"]?.toIntOrNull() ?: 0, l.query["bench"] ?: "", l.query["tts"] == "1"))
                "scene" -> l.path.firstOrNull()?.let { nav.navigate(Routes.live(it, l.query["autostart"] == "1", l.query["other"] ?: "", l.query["my"] ?: "", l.query["feed"] ?: "")) }
                "settings" -> nav.navigate(Routes.main(Tabs.SETTINGS)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "live" -> nav.navigate(Routes.main(Tabs.LIVE)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "library" -> nav.navigate(Routes.main(Tabs.LIBRARY)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "models" -> nav.navigate(Routes.models(l.query["install"] ?: ""))
                "gallery" -> nav.navigate(Routes.GALLERY)
                "onboarding" -> nav.navigate(Routes.onboarding(l.query["page"]?.toIntOrNull() ?: 0))
            }
            DeepLinks.consume()
        }
        NavHost(navController = nav, startDestination = Routes.main()) {
            composable(Routes.MAIN, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "0" })) { entry ->
                val initial = entry.savedStateHandle.get<String>("tab")?.toIntOrNull() ?: 0
                MainShell(nav, initial)
            }
            composable(Routes.LIVE, arguments = listOf(
                navArgument("sceneId") { type = NavType.StringType }, navArgument("autostart") { type = NavType.StringType; defaultValue = "false" },
                navArgument("other") { type = NavType.StringType; defaultValue = "" }, navArgument("my") { type = NavType.StringType; defaultValue = "" },
                navArgument("feed") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                val sceneId = entry.savedStateHandle.get<String>("sceneId") ?: "listen"
                val auto = entry.savedStateHandle.get<String>("autostart") == "true"
                val other = entry.savedStateHandle.get<String>("other").orEmpty(); val my = entry.savedStateHandle.get<String>("my").orEmpty()
                val feed = entry.savedStateHandle.get<String>("feed").orEmpty()
                LiveSessionRouter(nav, sceneId, auto, other, my, feed)
            }
            composable(Routes.GALLERY) { DesignSystemGallery(onBack = { nav.popBackStack() }) }
            composable(Routes.ONBOARDING, arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "0" })) { entry ->
                OnboardingScreen(initialPage = entry.savedStateHandle.get<String>("page")?.toIntOrNull() ?: 0, onDone = { nav.popBackStack() }, onOpenModels = { nav.navigate(Routes.models()) })
            }
            composable(Routes.MODELS, arguments = listOf(navArgument("install") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val install = entry.savedStateHandle.get<String>("install").orEmpty().split(",").filter { it.isNotBlank() }
                ModelsScreen(onBack = { nav.popBackStack() }, autoInstall = install)
            }
            composable(
                Routes.SELFTEST,
                arguments = listOf(
                    navArgument("autostart") { type = NavType.StringType; defaultValue = "false" },
                    navArgument("stop") { type = NavType.StringType; defaultValue = "0" },
                    navArgument("bench") { type = NavType.StringType; defaultValue = "" },
                    navArgument("tts") { type = NavType.StringType; defaultValue = "false" },
                ),
            ) { entry ->
                val auto = entry.savedStateHandle.get<String>("autostart") == "true"
                val stopAfter = entry.savedStateHandle.get<String>("stop")?.toIntOrNull() ?: 0
                val benchFile = entry.savedStateHandle.get<String>("bench")?.takeIf { it.isNotBlank() }
                val ttsTest = entry.savedStateHandle.get<String>("tts") == "true"
                AudioSelfTestScreen(onBack = { nav.popBackStack() }, autostart = auto, stopAfterSec = stopAfter, benchFile = benchFile, ttsTest = ttsTest)
            }
        }
    }
}

/** 四 Tab 壳（14 篇 §1 ①）：浮动玻璃胶囊 Tab 栏，内容从下面滚过。 */
@Composable
fun MainShell(nav: NavHostController, initialTab: Int) {
    var tab by rememberSaveable { mutableStateOf(initialTab.coerceIn(0, 3)) }
    val tabs = listOf(SceneTab("场景", SceneIcons.Scenes), SceneTab("实时", SceneIcons.Waveform), SceneTab("资料库", SceneIcons.Library), SceneTab("设置", SceneIcons.Sliders))
    val overlay = remember { mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null) }
    CompositionLocalProvider(LocalShellOverlay provides overlay) {
        Box(Modifier.fillMaxSize()) {
            GlassScaffold(bottomBar = { SceneTabBar(tabs, tab, onSelect = { tab = it }) }) { TabContent(nav, tab, onSelectTab = { tab = it }) }
            overlay.value?.let { it() }   // sheet / 压暗层盖在 Tab 栏之上
        }
    }
}

@Composable
private fun TabContent(nav: NavHostController, tab: Int, onSelectTab: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tabs.HOME -> HomeTab(
                onOpenScene = { nav.navigate(Routes.live(it)) },
                onOpenOnboarding = { nav.navigate(Routes.onboarding()) },
                onOpenLiveTab = { onSelectTab(Tabs.LIVE) },
            )
            Tabs.LIVE -> LiveTab(onStart = { sceneId -> nav.navigate(Routes.live(sceneId)) })
            Tabs.LIBRARY -> LibraryTab()
            else -> SettingsTab(
                onOpenModels = { nav.navigate(Routes.models()) },
                onOpenSelfTest = { nav.navigate(Routes.selfTest()) },
                onOpenGallery = { nav.navigate(Routes.GALLERY) },
                onOpenOnboarding = { nav.navigate(Routes.onboarding()) },
            )
        }
    }
}

/** 按场景的 liveModeId 分发到对应会话页：M0 / M1 → LiveM0Screen（M1 的耳听·面屏在 I4 单独成页），M4 → LiveM4Screen，其余占位。 */
@Composable
private fun LiveSessionRouter(nav: NavHostController, sceneId: String, autostart: Boolean, other: String, my: String, feed: String) {
    val scene = Scenes.byId(sceneId)
    val mode = scene?.liveModeId?.let { ModeSpecs.byId(it) }
    val back: () -> Unit = { nav.popBackStack() }
    when (mode?.id) {
        "M4" -> LiveM4Screen(sceneId = sceneId, onBack = back, onOpenModels = { nav.navigate(Routes.models()) }, autostart = autostart, otherLang = other, myLang = my, feed = feed)
        "M0", "M1", "M3" -> LiveConversationScreen(sceneId = sceneId, onBack = back, onOpenModels = { nav.navigate(Routes.models()) }, autostart = autostart, otherLang = other, myLang = my, feed = feed,
            onOpenQuickPhrase = { nav.navigate(Routes.live(Scenes.quickPhrase.id)) { popUpTo(Routes.LIVE) { inclusive = true } } })   // M4 替换 M0，不叠在其上
        else -> PlaceholderSessionScreen(title = scene?.name ?: sceneId, note = "该场景在后续里程碑落地（会议 I5 / 屏内字幕 I6）", onBack = back)
    }
}
