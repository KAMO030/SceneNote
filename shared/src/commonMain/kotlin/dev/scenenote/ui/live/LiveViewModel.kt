package dev.scenenote.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.asr.LoadPlan
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.RouteState
import dev.scenenote.core.egress.ConsentRegistry
import dev.scenenote.core.model.Interaction
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.ModeSpec
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MediaKey
import dev.scenenote.core.platform.MediaKeys
import dev.scenenote.core.platform.Posture
import dev.scenenote.core.platform.PostureSensor
import dev.scenenote.core.platform.ScreenKeeper
import dev.scenenote.core.platform.ThermalLevel
import dev.scenenote.core.platform.ThermalMonitor
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.live.FastPath
import dev.scenenote.live.LiveLine
import dev.scenenote.live.LiveSessionMachine
import dev.scenenote.live.LiveTranscriber
import dev.scenenote.live.Phrases
import dev.scenenote.live.PipelineHealth
import dev.scenenote.tts.SherpaTts
import dev.scenenote.tts.SystemTtsProvider
import dev.scenenote.tts.TtsPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

data class LiveUiState(
    val scene: ScenePreset? = null,
    val mode: ModeSpec? = null,
    val state: LiveState = LiveState.Idle,
    val route: RouteState? = null,
    val myLang: String = "zh-CN",
    val otherLang: String = "en",
    val hint: String = "",
    val engine: LocalEngineState = LocalEngineState.Unloaded,
    val lines: List<LiveLine> = emptyList(),
    val partial: String = "",
    val health: PipelineHealth = PipelineHealth(),
    val voiceOut: Boolean = true,
    val playing: String? = null,
    val error: String? = null,
    /** M4：按住说话中。 */
    val holding: Boolean = false,
    /** VAD：有人在说（M1 呼吸灯）。 */
    val speaking: Boolean = false,
    val posture: Posture = Posture.UNKNOWN,
    val autoPosture: Boolean = true,
    /** M1 对方半屏：礼貌卡显示期（进入 M1 后 3 s）。 */
    val politeCard: Boolean = false,
    /** 判向固定（退化单工）；null = 自动。 */
    val fixedDirection: Speaker? = null,
    /** 连续纠错 3 次后的「固定方向？」建议。 */
    val suggestFixed: Boolean = false,
    /** M3 外放（黄标）开关：等价 voiceOut，但命名与原型一致。 */
    val speakerOut: Boolean = false,
) {
    /** 当前句（最新一行）与历史句，供 M0 / M4 布局。 */
    val current: LiveLine? get() = lines.lastOrNull()
    val history: List<LiveLine> get() = lines.dropLast(1)
    /** 对方最近说的一句（M1 上 1/3「对方 → 我 已进耳机」）/ 我最近说的一句（对方半屏大字）。 */
    val lastOther: LiveLine? get() = lines.lastOrNull { it.speaker == Speaker.OTHER }
    val lastMe: LiveLine? get() = lines.lastOrNull { it.speaker == Speaker.ME }
}

/** 实时会话 VM：状态机（路由 / 打断）+ 采集识别 + 快路径（判向 / 翻译 / TTS / 播放）+ 姿态 / 热 / 媒体键。 */
class LiveViewModel(
    private val machine: LiveSessionMachine,
    audio: AudioFactory,
    private val appPaths: AppPaths,
    private val settings: AppSettings,
    private val engine: SherpaAsrEngine,
    private val fastPath: FastPath,
    private val sherpaTts: SherpaTts,
    private val systemTts: SystemTtsProvider,
    private val consent: ConsentRegistry,
    private val posture: PostureSensor,
    private val thermal: ThermalMonitor,
    private val mediaKeys: MediaKeys,
    private val screen: ScreenKeeper,
    private val repo: dev.scenenote.core.db.SessionRepository,
    private val glossary: dev.scenenote.core.db.GlossaryRepository,
    private val scenes: dev.scenenote.core.scene.SceneStore,
) : ViewModel() {
    private val routeManager = audio.routeManager()
    private val transcriber = LiveTranscriber(audio, engine, viewModelScope) { onAsr(it) }
    private val _ui = MutableStateFlow(LiveUiState(myLang = settings.myLang, otherLang = settings.otherLang, autoPosture = settings.autoPosture))
    val ui: StateFlow<LiveUiState> = _ui.asStateFlow()
    private var sessionId = Uuid.random().toString()
    /** 验收：用 bench 目录里的 WAV 代替麦克风。 */
    var feedFile: String? = null
    private var lastPartialAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var postureJob: Job? = null

    init {
        machine.state.onEach { s ->
            _ui.value = _ui.value.copy(state = s, hint = hintFor(s))
            if (s is LiveState.Live) { startTranscribing(); mediaKeys.activate(_ui.value.scene?.name ?: "场记"); if (pendingPoliteCard) startPoliteCard() }
            else if (s == LiveState.Idle || s is LiveState.Paused) { transcriber.stop(); transcribing = false }   // 暂停期间媒体键仍接管（单击 = 继续）；只在 end() 释放
        }.launchIn(viewModelScope)
        routeManager.current.onEach { r -> _ui.value = _ui.value.copy(route = r) }.launchIn(viewModelScope)
        engine.state.onEach { e -> _ui.value = _ui.value.copy(engine = e) }.launchIn(viewModelScope)
        fastPath.lines.onEach { l -> _ui.value = _ui.value.copy(lines = l) }.launchIn(viewModelScope)
        fastPath.partial.onEach { p -> onPartial(p) }.launchIn(viewModelScope)
        fastPath.health.onEach { h -> _ui.value = _ui.value.copy(health = h) }.launchIn(viewModelScope)
        fastPath.playing.onEach { p -> _ui.value = _ui.value.copy(playing = p) }.launchIn(viewModelScope)
        fastPath.speaking.onEach { s -> _ui.value = _ui.value.copy(speaking = s) }.launchIn(viewModelScope)
        transcriber.error.onEach { e -> if (e != null) _ui.value = _ui.value.copy(error = e) }.launchIn(viewModelScope)
        combine(thermal.level, thermal.lowBattery) { l, b -> l to b }.onEach { (l, b) -> fastPath.setThermal(l, b) }.launchIn(viewModelScope)
        posture.posture.onEach { p -> _ui.value = _ui.value.copy(posture = p); onPosture(p) }.launchIn(viewModelScope)
        mediaKeys.events.onEach { onMediaKey(it) }.launchIn(viewModelScope)
    }

    private fun onAsr(ev: dev.scenenote.asr.AsrEvent) = fastPath.onAsr(ev)

    /** SERIOUS 及以上：partial 刷新降到 5 Hz（规格 §5.5）。 */
    private fun onPartial(p: String) {
        val h = _ui.value.health
        if (h.thermal >= ThermalLevel.SERIOUS || h.lowBattery) {
            val now = TimeSource.Monotonic.markNow()
            val last = lastPartialAt
            if (p.isNotEmpty() && last != null && last.elapsedNow().inWholeMilliseconds < 200) return
            lastPartialAt = now
        }
        _ui.value = _ui.value.copy(partial = p)
    }

    private var transcribing = false
    private fun startTranscribing() {
        if (transcribing) return
        transcribing = true
        viewModelScope.launch {
            val feed = feedFile?.let { if (it.startsWith("/")) it else appPaths.join(appPaths.benchDir, it) }
            runCatching { transcriber.start(sourceLang(), feed) }
                .onFailure { _ui.value = _ui.value.copy(error = it.message ?: it.toString()); transcribing = false }
        }
    }

    /** 识别语言：仅听 = 对方语言；速译 / 对话 = 我的语言（对话模式 zipformer 中英双语，判向靠脚本 + 声纹）。 */
    private fun sourceLang(): String = when (_ui.value.mode?.interaction) {
        Interaction.SIMPLEX_IN -> _ui.value.otherLang
        else -> _ui.value.myLang
    }

    private var entered = false
    /**
     * 进页一次性初始化（同一个 VM 实例只执行一次：从模型页返回 / Activity 重建不会重复 trigger）。
     * 会清掉上一场会话残留在单例 FastPath 里的句子。
     */
    fun enter(sceneId: String, myLang: String = "", otherLang: String = "", feed: String = "", autostart: Boolean = false, voiceOut: Boolean? = null, initialMode: String = "") {
        if (entered) return
        entered = true
        dev.scenenote.core.Diag.log("live", "vm.enter scene=$sceneId autostart=$autostart mode=$initialMode")
        sessionStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        load(sceneId)
        // 入口指定了模式（实时 Tab「双屏」）：直接换成该模式，不触发姿态规则
        if (initialMode.isNotBlank() && initialMode != _ui.value.mode?.id) runCatching { ModeSpecs.byId(initialMode) }.getOrNull()?.let { m ->
            _ui.value = _ui.value.copy(mode = m); configureFastPath(); applyModeEffects(m.id, entering = false)
        }
        feedFile = feed.takeIf { it.isNotBlank() }
        // 深链 / 验收传入的语言只作用于本场会话，不改用户设置
        if (myLang.isNotBlank()) _ui.value = _ui.value.copy(myLang = myLang)
        if (otherLang.isNotBlank()) _ui.value = _ui.value.copy(otherLang = otherLang)
        if (myLang.isNotBlank() || otherLang.isNotBlank()) configureFastPath()
        if (voiceOut != null) setVoiceOut(voiceOut)
        thermal.start()
        if (autostart) trigger() else prepare()
    }

    /** 进页预热：装载识别模型（对话模式带声纹）+ 等系统 TTS 探测完，按下「开始 / 按住说话」时不再等 1–2 s。 */
    fun prepare() {
        val src = sourceLang()
        viewModelScope.launch {
            engine.load(plan(src))
            systemTts.awaitReady()
        }
    }

    /** 声纹：自动判向开着就装（对话复核 / M4 注册「我」/ M0 过滤我的声音，≈ 28 MB）。 */
    private fun plan(src: String) = LoadPlan.forLang(src, speaker = settings.directionAuto)

    fun load(sceneId: String) {
        fastPath.clear()
        val scene = scenes.resolve(sceneId)   // 内置或自定义（复制一张再改）
        val mode = scene?.liveModeId?.let { ModeSpecs.byId(it) }
        val other = scene?.langChips?.firstOrNull { it.default }?.tag?.takeIf { mode?.interaction == Interaction.SIMPLEX_IN } ?: settings.otherLang
        _ui.value = _ui.value.copy(scene = scene, mode = mode, myLang = settings.myLang, otherLang = other, fixedDirection = if (settings.directionAuto) null else Speaker.OTHER)
        configureFastPath()
        mode?.id?.let { applyModeEffects(it, entering = false) }   // 场景初始就是 M1 时同样常亮 / 礼貌卡（礼貌卡在进入 Live 时起计时）
    }

    private fun configureFastPath() {
        val mode = _ui.value.mode ?: return
        fastPath.configure(mode, _ui.value.myLang, _ui.value.otherLang, _ui.value.voiceOut, TtsPreference.of(settings.ttsPreference), sessionId, engine.speakerExtractor)
        fastPath.fixDirection(_ui.value.fixedDirection)
    }

    /**
     * 同一会话内切模式（M0 ⇄ M1 ⇄ M3，规格 §3.1 升级阶梯）：不重启音频；M1 / M4 常亮最亮；M1 进入时对方半屏先显示礼貌卡 3 s。
     * M3 是文本模式：默认不出声（外放为黄标开关）。用户手动切换会取消挂起的姿态自动切换。
     */
    fun switchMode(modeId: String) = switchMode(modeId, byPosture = false)

    /** 只有由姿态自动进入的 M1 才会在放平 / 入袋时自动退回 M0；手动或场景初始进入的 M1（例如平放桌上使用）不自动降。 */
    private var m1ByPosture = false
    private fun switchMode(modeId: String, byPosture: Boolean) {
        val mode = runCatching { ModeSpecs.byId(modeId) }.getOrNull() ?: return
        if (_ui.value.mode?.id == modeId) return
        postureJob?.cancel()
        m1ByPosture = byPosture && modeId == "M1"
        val voice = when (modeId) { "M3" -> _ui.value.speakerOut; else -> _ui.value.voiceOut }
        _ui.value = _ui.value.copy(mode = mode)
        _ui.value = _ui.value.copy(hint = hintFor(_ui.value.state))
        fastPath.switchMode(mode, voice)
        applyModeEffects(modeId, entering = true)
        if (_ui.value.state !is LiveState.Live && _ui.value.state != LiveState.Arming) trigger()
    }

    private var politeJob: Job? = null
    /** 模式副作用：常亮 / 最亮、M1 礼貌卡 3 s + 可选开场白。entering = false 表示场景初始模式（礼貌卡等进入 Live 再起）。 */
    private fun applyModeEffects(modeId: String, entering: Boolean) {
        val keep = modeId == "M1" || modeId == "M4" || modeId == "M3"
        screen.keepAwake(keep); screen.maxBrightness(modeId == "M1" || modeId == "M4")
        politeJob?.cancel()
        if (modeId == "M1") {
            if (entering) startPoliteCard()
            else pendingPoliteCard = true
        } else _ui.value = _ui.value.copy(politeCard = false)
    }
    private var pendingPoliteCard = false
    private fun startPoliteCard() {
        pendingPoliteCard = false
        _ui.value = _ui.value.copy(politeCard = true)
        politeJob = viewModelScope.launch { delay(3_000); _ui.value = _ui.value.copy(politeCard = false) }
        if (settings.politeOpener) fastPath.speakText(Phrases.opener(_ui.value.otherLang), _ui.value.otherLang, "opener-${Uuid.random()}", gainDb = -6f)
    }

    /** 姿态（03 篇 §3.3）：M0 且开启自动 → 竖直稳定 1.5 s 进 M1；M1 时放平 / 入袋 → 回 M0。只在 Live 期生效。 */
    private fun onPosture(p: Posture) {
        if (!_ui.value.autoPosture || _ui.value.state !is LiveState.Live) return
        postureJob?.cancel()
        val modeId = _ui.value.mode?.id
        postureJob = viewModelScope.launch {
            fun still(expect: String) = _ui.value.mode?.id == expect && _ui.value.autoPosture && _ui.value.state is LiveState.Live   // 延时后复核：用户可能已手动切换
            when {
                modeId == "M0" && p == Posture.UPRIGHT -> { delay(1_500); if (posture.posture.value == Posture.UPRIGHT && still("M0")) switchMode("M1", byPosture = true) }
                modeId == "M1" && m1ByPosture && (p == Posture.FLAT || p == Posture.POCKET) -> { delay(1_000); if (posture.posture.value != Posture.UPRIGHT && still("M1")) switchMode("M0", byPosture = true) }
            }
        }
    }

    private fun onMediaKey(k: MediaKey) {
        when (k) {
            MediaKey.PLAY_PAUSE -> if (_ui.value.state is LiveState.Live) pause() else trigger()
            MediaKey.NEXT -> { fastPath.flush("skip"); if (_ui.value.mode?.interaction == Interaction.CONVERSATION) flipLast() }   // 双击 = 跳过当前译文（对话：翻转上句重译）
            MediaKey.PREVIOUS -> _ui.value.lastOther?.let { fastPath.speak(it.id) }   // 三击 = 重播上一句
        }
    }

    fun setAutoPosture(on: Boolean) { settings.autoPosture = on; _ui.value = _ui.value.copy(autoPosture = on) }
    fun setOtherLang(lang: String) { _ui.value = _ui.value.copy(otherLang = lang); configureFastPath() }
    fun setMyLang(lang: String) { _ui.value = _ui.value.copy(myLang = lang); settings.myLang = lang; configureFastPath() }
    fun swapLangs() { val u = _ui.value; _ui.value = u.copy(myLang = u.otherLang, otherLang = u.myLang); configureFastPath() }
    fun setVoiceOut(on: Boolean) { _ui.value = _ui.value.copy(voiceOut = on); fastPath.setVoiceOut(on) }
    /** M3 黄标外放：开 = 对方译文也出声（默认关，规格：外放只作显式黄标降级）。 */
    fun setSpeakerOut(on: Boolean) { _ui.value = _ui.value.copy(speakerOut = on); fastPath.setVoiceOut(on) }

    /** 翻转一行（M3 气泡左右滑 / 翻转按钮）：反向重译；连续 3 次建议固定方向。 */
    fun flip(lineId: String) { val suggest = fastPath.flip(lineId); if (suggest) _ui.value = _ui.value.copy(suggestFixed = true) }
    fun flipLast() { _ui.value.current?.let { flip(it.id) } }
    /** 会话内固定方向（不写全局设置；全局开关在设置页）。 */
    fun fixDirection(speaker: Speaker?) { _ui.value = _ui.value.copy(fixedDirection = speaker, suggestFixed = false); fastPath.fixDirection(speaker) }
    fun forgetVoice() = fastPath.forgetVoice()

    /** 对方按「我不用了」：播一句对方语言的退出语（唯一默认允许的外放，−6 dB），然后退回 M0（规格 §4.4）。 */
    fun politeExit(endSession: Boolean = false) {
        fastPath.speakText(Phrases.exitPhrase(_ui.value.otherLang), _ui.value.otherLang, "exit-${Uuid.random()}", gainDb = -6f)
        if (endSession) end() else switchMode("M0")
    }

    /**
     * 场景卡自带隐私档（04 篇：卡上明示"仅文本上云"）：全局默认是"逐段授权"时，进入这类场景即视为对本会话授权，结束会话撤销；
     * 全局是"锁定"时不放行（EgressGate 先判锁定）。
     */
    private fun applyScenePrivacy() {
        val scene = _ui.value.scene ?: return
        if (scene.privacy.allowsInternetText && settings.privacy.value is PrivacyMode.LocalWithPerSegmentConsent) consent.grantSession(sessionId)
    }

    /** 开始：按场景语言与内存分级选装载计划；预热目标语言的端侧语音包；再交给状态机。 */
    fun trigger() {
        val src = sourceLang()
        dev.scenenote.core.Diag.log("live", "vm.trigger scene=${_ui.value.scene?.id} mode=${_ui.value.mode?.id} state=${_ui.value.state}")
        applyScenePrivacy()
        viewModelScope.launch {
            // 术语表按场景词袋注入（我 → 对方 与 对方 → 我 两个方向）+ 纠错映射
            runCatching {
                val bucket = _ui.value.scene?.hotwordBucket ?: "general"
                val my = _ui.value.myLang; val other = _ui.value.otherLang
                fastPath.setGlossary(mapOf(other to glossary.termsFor(bucket, other), my to glossary.termsFor(bucket, my)), glossary.corrections(bucket).map { it.wrong to it.right })
            }
            val es = engine.load(plan(src))
            val tgt = if (_ui.value.mode?.interaction == Interaction.SIMPLEX_OUT) _ui.value.otherLang else _ui.value.myLang
            systemTts.awaitReady()
            // 系统没有该语言的语音时才预热端侧包（只驻留一个 TTS）
            if (_ui.value.voiceOut && systemTts.get()?.supports(tgt) != true) runCatching { sherpaTts.preload(tgt) }
            configureFastPath()
            posture.start()
            machine.trigger()
        }
    }

    /** M4 按住说话：按下开始，松手收句（一句话 ≤ 3 步：进页 → 按住 → 松手）。 */
    fun holdStart() {; _ui.value = _ui.value.copy(holding = true); if (_ui.value.state !is LiveState.Live) trigger() }
    /** 松手：立即收句；随后停采集（麦克风不再常开），识别会话会先排空再关闭，Final 照常进快路径。引擎保持装载，下次按住立刻可用。 */
    fun holdEnd() {
        _ui.value = _ui.value.copy(holding = false)
        transcriber.forceEndpoint()
        viewModelScope.launch { delay(300); if (!_ui.value.holding) machine.end() }
    }

    /** M4 大字卡上的「朗读 · 外放」：朗读当前句译文；返回 false = 没有可用语音。 */
    fun speakCurrent(): Boolean = _ui.value.current?.let { fastPath.speak(it.id) } ?: false
    fun stopSpeaking() { fastPath.flush("user") }

    fun pause() { machine.end() }
    /** 会话是否已落库（结束时把对话行写进资料库；只存文字，对方音频不落盘）。 */
    private var persisted = false
    private var persistedId: String? = null
    val savedSessionId: String? get() = persistedId
    private var sessionStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
    /** 同步落库（在结束导航前 await），避免产物页读到半写入的会话。 */
    private suspend fun persist(): String? {
        if (persisted) return persistedId
        val lines = _ui.value.lines.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return null
        persisted = true
        val scene = _ui.value.scene; val mode = _ui.value.mode
        val id = sessionId; persistedId = id
        val title = (lines.firstOrNull { it.speaker == Speaker.OTHER }?.translation ?: lines.first().text).take(24)
        runCatching {
            repo.create(id, dev.scenenote.core.db.SessionKind.LIVE, scene?.id ?: "listen", mode?.id, _ui.value.otherLang, _ui.value.myLang, _ui.value.otherLang, engine.info, startedAt = sessionStartedAt)
            repo.addUtterances(id, lines)
            repo.end(id, title = "对话 · $title", summary = null)
        }.onFailure { persisted = false; persistedId = null }
        return persistedId
    }
    /** 结束并落库；返回落库的会话 id（没有内容则 null）。页面用它决定是否进对话卡片页。 */
    suspend fun endAndPersist(): String? { end(); return persist() }
    fun end() {
        transcribing = false; transcriber.stop(); fastPath.flush("end"); machine.end(); consent.revokeSession(sessionId)
        viewModelScope.launch { persist() }
        posture.stop(); postureJob?.cancel(); politeJob?.cancel(); screen.keepAwake(false); screen.maxBrightness(false); mediaKeys.deactivate()
    }
    fun clear() { fastPath.clear() }

    private fun hintFor(s: LiveState): String = when (s) {
        LiveState.Idle -> "按一下开始；戴耳机或外放都可以"
        LiveState.Arming -> "准备中…"
        is LiveState.Live -> when (_ui.value.mode?.interaction) { Interaction.SIMPLEX_IN -> "正在听对方"; Interaction.SIMPLEX_OUT -> "说一句，松手出字"; else -> "正在听" }
        is LiveState.Paused -> when (s.reason) { "call" -> "来电 / 系统打断，稍后继续"; else -> "已暂停" }
        LiveState.NeedForeground -> "预热已失效，解锁并点一下继续"
        LiveState.Degraded -> "请戴上耳机，或改用双屏"
        LiveState.Ending -> "正在整理会话…"
    }

    override fun onCleared() { end(); thermal.stop() }
}
