package dev.scenenote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.ktor.http.decodeURLQueryComponent
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
import dev.scenenote.ui.meeting.MeetingScreen
import dev.scenenote.ui.screen.ScreenFlowScreen
import dev.scenenote.ui.note.LiveEndScreen
import dev.scenenote.ui.note.MeetingResultScreen
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
    const val MAIN = "main?tab={tab}&key={key}"
    fun main(tab: Int = 0, openKey: Boolean = false) = "main?tab=$tab&key=$openKey"
    const val LIVE = "live/{sceneId}?autostart={autostart}&other={other}&my={my}&feed={feed}&mode={mode}"
    /** mode：M0 / M1 / M3 覆盖场景默认模式（实时 Tab「双屏」直接进双屏）。 */
    fun live(sceneId: String, autostart: Boolean = false, other: String = "", my: String = "", feed: String = "", mode: String = "") = "live/$sceneId?autostart=$autostart&other=$other&my=$my&feed=$feed&mode=$mode"
    const val GALLERY = "gallery"
    /** 会话产物页：按 session.kind 分发到纪要 / 对话卡片 / 字幕。 */
    const val NOTE = "note/{sessionId}"
    fun note(sessionId: String) = "note/$sessionId"
    const val MEETING = "meeting?autostart={autostart}"
    const val SCREEN = "screen?session={session}&shared={shared}&name={name}"
    fun screen(sessionId: String = "", shared: String = "", name: String = "") = "screen?session=$sessionId&shared=$shared&name=$name"
    /** Android S1 系统字幕（抓其他 App 的声音）。 */
    const val SYSTEM_CAPTION = "syscaption"
    const val LEDGER = "ledger"
    const val GLOSSARY = "glossary"
    fun meeting(autostart: Boolean = true) = "meeting?autostart=$autostart"
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
                "scene" -> l.path.firstOrNull()?.let { nav.navigate(Routes.live(it, l.query["autostart"] == "1", l.query["other"] ?: "", l.query["my"] ?: "", l.query["feed"] ?: "", l.query["mode"] ?: "")) }
                "settings" -> nav.navigate(Routes.main(Tabs.SETTINGS)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "live" -> nav.navigate(Routes.main(Tabs.LIVE)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "library" -> nav.navigate(Routes.main(Tabs.LIBRARY)) { popUpTo(Routes.MAIN) { inclusive = true } }
                "models" -> nav.navigate(Routes.models(l.query["install"] ?: ""))
                "gallery" -> nav.navigate(Routes.GALLERY)
                "note" -> l.path.firstOrNull()?.let { nav.navigate(Routes.note(it)) }
                "screen" -> nav.navigate(Routes.screen(shared = l.query["shared"] ?: "", name = l.query["name"] ?: ""))
                "syscaption" -> nav.navigate(Routes.SYSTEM_CAPTION)
                "ledger" -> nav.navigate(Routes.LEDGER)
                "glossary" -> nav.navigate(Routes.GLOSSARY)
                "onboarding" -> nav.navigate(Routes.onboarding(l.query["page"]?.toIntOrNull() ?: 0))
            }
            DeepLinks.consume()
        }
        // 首次启动先进新手引导（docs/15 §2）；引导结束写 onboardingDone，之后从设置 → 新手引导 可再看
        val settings = org.koin.compose.koinInject<dev.scenenote.core.settings.AppSettings>()
        val start = remember { if (settings.onboardingDone) Routes.main() else Routes.onboarding() }   // 只在首次组合决定，避免 NavGraph 重建
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.MAIN, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "0" }, navArgument("key") { type = NavType.StringType; defaultValue = "false" })) { entry ->
                val initial = entry.savedStateHandle.get<String>("tab")?.toIntOrNull() ?: 0
                val openKey = entry.savedStateHandle.get<String>("key") == "true"
                MainShell(nav, initial, openKey)
            }
            composable(Routes.LIVE, arguments = listOf(
                navArgument("sceneId") { type = NavType.StringType }, navArgument("autostart") { type = NavType.StringType; defaultValue = "false" },
                navArgument("other") { type = NavType.StringType; defaultValue = "" }, navArgument("my") { type = NavType.StringType; defaultValue = "" },
                navArgument("feed") { type = NavType.StringType; defaultValue = "" }, navArgument("mode") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                val sceneId = entry.savedStateHandle.get<String>("sceneId") ?: "listen"
                val auto = entry.savedStateHandle.get<String>("autostart") == "true"
                val other = entry.savedStateHandle.get<String>("other").orEmpty(); val my = entry.savedStateHandle.get<String>("my").orEmpty()
                val feed = entry.savedStateHandle.get<String>("feed").orEmpty()
                val mode = entry.savedStateHandle.get<String>("mode").orEmpty()
                LiveSessionRouter(nav, sceneId, auto, other, my, feed, mode)
            }
            composable(Routes.GALLERY) { DesignSystemGallery(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTE, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                val id = entry.savedStateHandle.get<String>("sessionId").orEmpty()
                NoteRouter(nav, id)
            }
            composable(Routes.SCREEN, arguments = listOf(navArgument("session") { type = NavType.StringType; defaultValue = "" }, navArgument("shared") { type = NavType.StringType; defaultValue = "" }, navArgument("name") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val shared = entry.savedStateHandle.get<String>("shared").orEmpty()
                val sharedName = entry.savedStateHandle.get<String>("name").orEmpty()
                if (shared.isNotBlank()) {   // 分享面板送来的视频：同一 NavBackStackEntry 的 VM，先喂路径再进流程（重建不重跑）
                    val svm: dev.scenenote.ui.screen.ScreenViewModel = org.koin.compose.viewmodel.koinViewModel()
                    LaunchedEffect(shared) {
                        val path = shared.decodeURLQueryComponent()
                        if (svm.ui.value.job.media?.path != path) svm.openShared(path, sharedName.takeIf { it.isNotBlank() }?.decodeURLQueryComponent() ?: path.substringAfterLast('/'))
                    }
                }
                ScreenFlowScreen(onBack = { nav.popBackStack() }, reopenSessionId = entry.savedStateHandle.get<String>("session").orEmpty(), onSystemCaption = { nav.navigate(Routes.SYSTEM_CAPTION) })
            }
            composable(Routes.SYSTEM_CAPTION) { dev.scenenote.ui.screen.SystemCaptionScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LEDGER) { dev.scenenote.ui.ledger.LedgerScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.GLOSSARY) { dev.scenenote.ui.glossary.GlossaryScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MEETING, arguments = listOf(navArgument("autostart") { type = NavType.StringType; defaultValue = "true" })) { entry ->
                val auto = entry.savedStateHandle.get<String>("autostart") != "false"
                MeetingScreen(onBack = { nav.popBackStack() }, onDone = { id -> nav.navigate(Routes.note(id)) { popUpTo(Routes.MEETING) { inclusive = true } } }, autostart = auto)
            }
            composable(Routes.ONBOARDING, arguments = listOf(navArgument("page") { type = NavType.StringType; defaultValue = "0" })) { entry ->
                OnboardingScreen(
                    initialPage = entry.savedStateHandle.get<String>("page")?.toIntOrNull() ?: 0,
                    onDone = {
                        settings.onboardingDone = true
                        // 首次启动时引导是栈底：用 main 替换；从设置进来时直接返回
                        if (!nav.popBackStack()) nav.navigate(Routes.main()) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    },
                    onOpenModels = { nav.navigate(Routes.models()) },
                    onOpenKey = {
                        settings.onboardingDone = true
                        nav.navigate(Routes.main(Tabs.SETTINGS, openKey = true)) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    },
                )
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
fun MainShell(nav: NavHostController, initialTab: Int, openKey: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(initialTab.coerceIn(0, 3)) }
    val tabs = listOf(SceneTab("场景", SceneIcons.Scenes), SceneTab("实时", SceneIcons.Waveform), SceneTab("资料库", SceneIcons.Library), SceneTab("设置", SceneIcons.Sliders))
    val overlay = remember { mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null) }
    CompositionLocalProvider(LocalShellOverlay provides overlay) {
        Box(Modifier.fillMaxSize()) {
            GlassScaffold(bottomBar = { SceneTabBar(tabs, tab, onSelect = { tab = it }) }) { TabContent(nav, tab, onSelectTab = { tab = it }, openKey = openKey) }
            overlay.value?.let { it() }   // sheet / 压暗层盖在 Tab 栏之上
        }
    }
}

@Composable
private fun TabContent(nav: NavHostController, tab: Int, onSelectTab: (Int) -> Unit, openKey: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tabs.HOME -> HomeTab(
                onOpenScene = { nav.navigate(Routes.live(it)) },
                onOpenOnboarding = { nav.navigate(Routes.onboarding()) },
                onOpenLiveTab = { onSelectTab(Tabs.LIVE) },
            )
            Tabs.LIVE -> LiveTab(onStart = { sceneId, mode -> nav.navigate(Routes.live(sceneId, mode = mode)) })
            Tabs.LIBRARY -> LibraryTab(onOpen = { nav.navigate(Routes.note(it)) })
            else -> SettingsTab(
                onOpenModels = { nav.navigate(Routes.models()) },
                onOpenSelfTest = { nav.navigate(Routes.selfTest()) },
                onOpenGallery = { nav.navigate(Routes.GALLERY) },
                onOpenOnboarding = { nav.navigate(Routes.onboarding()) },
                onOpenLedger = { nav.navigate(Routes.LEDGER) },
                onOpenGlossary = { nav.navigate(Routes.GLOSSARY) },
                openKeyOnEnter = openKey,
            )
        }
    }
}

/** 按场景的 liveModeId 分发到对应会话页：M0 / M1 → LiveM0Screen（M1 的耳听·面屏在 I4 单独成页），M4 → LiveM4Screen，其余占位。 */
@Composable
private fun LiveSessionRouter(nav: NavHostController, sceneId: String, autostart: Boolean, other: String, my: String, feed: String, modeOverride: String = "") {
    val store = org.koin.compose.koinInject<dev.scenenote.core.scene.SceneStore>()
    val scene = store.resolve(sceneId)   // 内置或「复制一张再改」的副本（副本按底版分发）
    val baseId = store.byId(sceneId)?.baseId ?: sceneId
    val mode = (modeOverride.takeIf { it.isNotBlank() } ?: scene?.liveModeId)?.let { runCatching { ModeSpecs.byId(it) }.getOrNull() }
    val back: () -> Unit = { nav.popBackStack() }
    if (baseId == Scenes.screenFile.id) { ScreenFlowScreen(onBack = back, onSystemCaption = { nav.navigate(Routes.SYSTEM_CAPTION) }); return }
    if (baseId == Scenes.meeting.id) { MeetingScreen(onBack = back, onDone = { id -> nav.navigate(Routes.note(id)) { popUpTo(Routes.LIVE) { inclusive = true } } }, autostart = autostart); return }
    when (mode?.id) {
        "M4" -> LiveM4Screen(sceneId = sceneId, onBack = back, onOpenModels = { nav.navigate(Routes.models()) }, autostart = autostart, otherLang = other, myLang = my, feed = feed)
        "M0", "M1", "M3" -> LiveConversationScreen(sceneId = sceneId, onBack = back, onOpenModels = { nav.navigate(Routes.models()) }, autostart = autostart, otherLang = other, myLang = my, feed = feed, initialMode = modeOverride,
            onEnded = { savedId -> if (savedId != null) nav.navigate(Routes.note(savedId)) { popUpTo(Routes.LIVE) { inclusive = true } } else nav.popBackStack() },
            onOpenQuickPhrase = { nav.navigate(Routes.live(Scenes.quickPhrase.id)) { popUpTo(Routes.LIVE) { inclusive = true } } })   // M4 替换 M0，不叠在其上
        else -> PlaceholderSessionScreen(title = scene?.name ?: sceneId, note = "稍后开放", onBack = back)
    }
}

/** 会话产物：录音 → 纪要；对话 → 对话卡片；字幕 → 纪要页（含 cue 列表，I6 后换字幕页）。 */
@Composable
private fun NoteRouter(nav: NavHostController, sessionId: String) {
    val repo = org.koin.compose.koinInject<dev.scenenote.core.db.SessionRepository>()
    var kind by remember { mutableStateOf<dev.scenenote.core.db.SessionKind?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        repeat(5) { kind = repo.byId(sessionId)?.kind; if (kind != null) return@LaunchedEffect; kotlinx.coroutines.delay(100) }   // 落库刚提交时重试几次
        missing = true
    }
    when {
        kind == dev.scenenote.core.db.SessionKind.LIVE -> LiveEndScreen(sessionId = sessionId, onBack = { nav.popBackStack() })
        kind == dev.scenenote.core.db.SessionKind.SCREEN -> ScreenFlowScreen(onBack = { nav.popBackStack() }, reopenSessionId = sessionId)
        kind != null -> MeetingResultScreen(sessionId = sessionId, onBack = { nav.popBackStack() })
        missing -> dev.scenenote.ui.live.PlaceholderSessionScreen(title = "记录", note = "找不到这条记录", onBack = { nav.popBackStack() })
        else -> Unit
    }
}
