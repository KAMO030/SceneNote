package dev.scenenote.ui.selftest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.AudioSelfTest
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCard
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.bench.AsrBench
import dev.scenenote.bench.AsrBenchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.load
import dev.scenenote.core.i18n.string
import dev.scenenote.core.i18n.uiText
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.getString
import dev.scenenote.core.i18n.stringResource

class AudioSelfTestViewModel(
    audio: AudioFactory, private val appPaths: AppPaths, private val bench: AsrBench,
    private val ttsRouter: dev.scenenote.tts.TtsRouter, private val queue: dev.scenenote.live.PlaybackQueue, private val settings: dev.scenenote.core.settings.AppSettings,
    private val systemTts: dev.scenenote.tts.SystemTtsProvider,
    val probe: dev.scenenote.bench.LatencyProbe,
    private val nmt: dev.scenenote.nmt.OnnxNmtTranslator,
) : ViewModel() {
    private val _mt = MutableStateFlow("")
    val mtUi: StateFlow<String> = _mt.asStateFlow()

    /** 离线翻译测试：中 → 英、英 → 中各两句，只走端侧 opus-mt（不看 Key）；每句耗时与结果写 bench/nmt-selftest.jsonl，方便真机验收贴数据。 */
    fun translateTest() {
        viewModelScope.launch {
            val cases = listOf(
                Triple("你好，很高兴见到你。", "zh-CN", "en"), Triple("这个价格太贵了，能不能便宜一点？", "zh-CN", "en"),
                Triple("Hello, nice to meet you.", "en", "zh-CN"), Triple("The meeting is at 3 pm tomorrow, please don't be late.", "en", "zh-CN"),
            )
            val missing = cases.filterNot { (_, s, t) -> nmt.supports(s, t) }.map { (_, s, t) -> "$s→$t" }.distinct()
            if (missing.isNotEmpty()) { _mt.value = getString(Res.string.diag_mt_missing, missing.joinToString(" ")); return@launch }
            val lines = mutableListOf<String>()
            _mt.value = "…"
            for ((text, src, tgt) in cases) {
                val line = runCatching {
                    val r = nmt.translate(dev.scenenote.translate.MtRequest(text, src, tgt))
                    appPaths.ensureDir(appPaths.benchDir)
                    appPaths.appendText(appPaths.join(appPaths.benchDir, "nmt-selftest.jsonl"), """{"src":"$src","tgt":"$tgt","text":${jsonStr(text)},"out":${jsonStr(r.text)},"model":"${r.model}","ms":${r.latencyMs},"in":${r.inputTokens},"outTok":${r.outputTokens},"ort":"${dev.scenenote.nmt.Ort.version}"}""" + "\n")
                    "$text → ${r.text}  (${r.model}, ${r.latencyMs} ms)"
                }.getOrElse { "$text → ✗ ${it.message}" }
                lines += line
                _mt.value = lines.joinToString("\n")
            }
            nmt.unloadAll()
        }
    }
    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** 关卡 B：会话里每句「句尾 → 首帧写入」的 P50 / P95（本机口径：1300 / 2000 ms）。 */
    val latency: StateFlow<dev.scenenote.bench.BenchSummary> = probe.records.map { dev.scenenote.bench.BenchSummary.of(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, dev.scenenote.bench.BenchSummary.of(emptyList()))
    fun clearLatency() = probe.clear()
    val test = AudioSelfTest(audio, appPaths, viewModelScope)
    private val _bench = MutableStateFlow<BenchUi>(BenchUi.Idle)
    val benchUi: StateFlow<BenchUi> = _bench.asStateFlow()
    private val _tts = MutableStateFlow("")
    /** 朗读测试的结果行（引擎 / 首音 / 总耗时）。 */
    val ttsUi: StateFlow<String> = _tts.asStateFlow()

    /** 朗读测试：中英各一句，走 TtsRouter（系统优先 → 端侧包）→ 播放队列 → 当前输出设备。 */
    fun speakTest() {
        viewModelScope.launch {
            systemTts.awaitReady()
            val pref = dev.scenenote.tts.TtsPreference.of(settings.ttsPreference)
            val zh = ttsRouter.choose("zh-CN", pref); val en = ttsRouter.choose("en", pref)
            if (!zh.available && !en.available) { _tts.value = getString(Res.string.diag_no_voice, zh.reason.load()); return@launch }
            _tts.value = getString(Res.string.diag_synthesizing, zh.engine?.id ?: zh.reason.load(), en.engine?.id ?: en.reason.load())
            val results = mutableListOf<String>()
            val job = launch {
                queue.events.collect { ev ->
                    when (ev) {
                        is dev.scenenote.live.PlaybackEvent.Started -> results += getString(Res.string.diag_tts_started, ev.utteranceId, ev.firstChunkMs.toInt())
                        is dev.scenenote.live.PlaybackEvent.Done -> results += getString(Res.string.diag_tts_done, ev.utteranceId, ev.stats.totalMs.toInt(), (ev.stats.samples / 16).toInt(), ev.stats.engineId)
                        is dev.scenenote.live.PlaybackEvent.Failed -> results += getString(Res.string.diag_tts_failed, ev.utteranceId, ev.reason)
                        is dev.scenenote.live.PlaybackEvent.Skipped -> results += getString(Res.string.diag_tts_skipped, ev.utteranceId, ev.reason)
                    }
                    _tts.value = results.joinToString("\n")
                }
            }
            zh.engine?.let { queue.enqueue(dev.scenenote.tts.TtsRequest("你好，欢迎使用场记。现在是语音自检。", "zh-CN", "tts-zh"), it) }
            en.engine?.let { queue.enqueue(dev.scenenote.tts.TtsRequest("Hello, this is the voice self test.", "en", "tts-en"), it) }
            kotlinx.coroutines.delay(30_000); job.cancel()
        }
    }
    fun runBench() {
        val pcm = test.lastPcm() ?: run { _bench.value = BenchUi.Error(UiText.res(Res.string.diag_record_first)); return }
        runBenchPcm(pcm)
    }
    /** 用 WAV 文件跑基准（验收脚本：scenenote://selftest?bench=<路径或 bench 目录内文件名>）。 */
    fun runBenchFile(path: String) {
        _bench.value = BenchUi.Running
        viewModelScope.launch {
            _bench.value = runCatching {
                val full = if (path.startsWith("/")) path else appPaths.join(appPaths.benchDir, path)
                BenchUi.Done(bench.run(dev.scenenote.bench.WavIo.readPcm16k(full)))
            }.getOrElse { BenchUi.Error(it.uiText()) }
        }
    }
    private fun runBenchPcm(pcm: ShortArray) {
        _bench.value = BenchUi.Running
        viewModelScope.launch { _bench.value = runCatching { BenchUi.Done(bench.run(pcm)) }.getOrElse { BenchUi.Error(it.uiText()) } }
    }
    override fun onCleared() { test.release() }
}

sealed interface BenchUi { data object Idle : BenchUi; data object Running : BenchUi; data class Done(val r: AsrBenchResult) : BenchUi; data class Error(val msg: UiText) : BenchUi }

/** 设置 → 诊断（给开发者 / 反馈用）：录音 / 朗读 / 延迟 / 识别基准四张卡，数据保留，说明各一行。 */
@Composable
fun AudioSelfTestScreen(onBack: () -> Unit, autostart: Boolean = false, stopAfterSec: Int = 0, benchFile: String? = null, ttsTest: Boolean = false, mtTest: Boolean = false, vm: AudioSelfTestViewModel = koinViewModel()) {
    val c = SceneTheme.colors
    val s by vm.test.state.collectAsState()
    val bench by vm.benchUi.collectAsState()
    val tts by vm.ttsUi.collectAsState()
    val mt by vm.mtUi.collectAsState()
    val lat by vm.latency.collectAsState()
    var ran by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {   // 深链参数只执行一次（重建不重复跑基准 / 朗读）
        if (ran) return@LaunchedEffect
        ran = true
        if (!benchFile.isNullOrBlank()) vm.runBenchFile(benchFile)
        if (ttsTest) vm.speakTest()
        if (mtTest) vm.translateTest()
    }
    LaunchedEffect(autostart, stopAfterSec) {
        if (autostart) {
            vm.test.start()
            if (stopAfterSec > 0) { kotlinx.coroutines.delay(stopAfterSec * 1000L); vm.test.stop() }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.test.stop() } }
    GlassScaffold(topBar = { SceneNavBar(title = stringResource(Res.string.settings_diagnostics), onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 104.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            // 录音：电平 + 路由 + 录音 / 测试音
            SceneCard {
                SceneText(stringResource(Res.string.diag_recording), style = SceneTheme.type.headline)
                SceneText(stringResource(Res.string.diag_recording_desc), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                Meter(s.rmsDb)
                SceneText(stringResource(Res.string.diag_levels, fmtDb(s.rmsDb), fmtDb(s.peakDb), s.frames.toInt()), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                s.route?.let { r -> SceneText(stringResource(Res.string.diag_route, r.input.toString(), r.output.toString()), style = SceneTheme.type.footnote, color = c.secondaryLabel) }
                Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
                    if (!s.recording) SceneButton(stringResource(Res.string.diag_start_recording), onClick = { vm.test.start() }, style = ButtonStyle.Prominent, icon = SceneIcons.Mic, modifier = Modifier.weight(1f))
                    else SceneButton(stringResource(Res.string.syscap_stop), onClick = { vm.test.stop() }, style = ButtonStyle.Prominent, icon = SceneIcons.Stop, modifier = Modifier.weight(1f))
                    SceneButton(stringResource(if (s.tonePlaying) Res.string.live_playing else Res.string.diag_test_tone), onClick = { vm.test.playTone() }, style = ButtonStyle.Gray, enabled = !s.tonePlaying, icon = SceneIcons.Speaker)
                }
                s.lastFile?.let { SceneText(stringResource(Res.string.diag_saved, it, (s.lastFileBytes / 1024).toInt()), style = SceneTheme.type.caption1, color = c.secondaryLabel) }
                if (autostart && stopAfterSec > 0) SceneText(stringResource(Res.string.diag_auto_stop, stopAfterSec), style = SceneTheme.type.caption1, color = c.secondaryLabel)
                s.error?.let { SceneText(it, style = SceneTheme.type.footnote, color = c.destructive) }
            }

            // 朗读：中英各一句
            SceneCard {
                SceneText(stringResource(Res.string.settings_tts_header), style = SceneTheme.type.headline)
                SceneText(stringResource(Res.string.diag_tts_desc), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                SceneButton(stringResource(Res.string.diag_tts_test), onClick = { vm.speakTest() }, style = ButtonStyle.Tinted, icon = SceneIcons.Headphones, modifier = Modifier.fillMaxWidth())
                if (tts.isNotBlank()) SceneText(tts, style = SceneTheme.type.footnote)
            }

            // 离线翻译：只走端侧模型，四句固定文本
            SceneCard {
                SceneText(stringResource(Res.string.diag_mt_header), style = SceneTheme.type.headline)
                SceneText(stringResource(Res.string.diag_mt_desc), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                SceneButton(stringResource(Res.string.diag_mt_test), onClick = { vm.translateTest() }, style = ButtonStyle.Tinted, modifier = Modifier.fillMaxWidth())
                if (mt.isNotBlank()) SceneText(mt, style = SceneTheme.type.footnote)
            }

            // 延迟：来自实时会话的 LatencyProbe
            SceneCard {
                SceneText(stringResource(Res.string.diag_latency), style = SceneTheme.type.headline)
                SceneText(stringResource(Res.string.diag_latency_desc), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                if (lat.count == 0) SceneText(stringResource(Res.string.diag_latency_empty), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                else {
                    val ok = (lat.e2eP50 ?: Long.MAX_VALUE) <= 1300 && (lat.e2eP95 ?: Long.MAX_VALUE) <= 2000
                    SceneText(stringResource(Res.string.diag_latency_summary, lat.count, (lat.e2eP50 ?: "—").toString(), (lat.e2eP95 ?: "—").toString(), stringResource(if (ok) Res.string.diag_pass else Res.string.diag_fail)), style = SceneTheme.type.footnote, color = if (ok) c.tint else c.destructive)
                    SceneText(stringResource(Res.string.diag_latency_stages, (lat.asrP50 ?: "—").toString(), (lat.mtP50 ?: "—").toString(), (lat.ttsP50 ?: "—").toString()), style = SceneTheme.type.footnote)
                    SceneButton(stringResource(Res.string.common_clear), onClick = { vm.clearLatency() }, style = ButtonStyle.Gray, modifier = Modifier.fillMaxWidth())
                }
            }

            // 识别基准：用上一段录音跑本机识别
            SceneCard {
                SceneText(stringResource(Res.string.diag_bench), style = SceneTheme.type.headline)
                SceneText(stringResource(Res.string.diag_bench_desc), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                SceneButton(
                    if (bench is BenchUi.Running) stringResource(Res.string.diag_bench_running) else stringResource(Res.string.diag_bench_run, s.recordedSec.toInt()),
                    onClick = { vm.runBench() }, style = ButtonStyle.Tinted,
                    enabled = !s.recording && s.recordedSec >= 3 && bench !is BenchUi.Running,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val b = bench) {
                    is BenchUi.Done -> {
                        val r = b.r
                        SceneText(stringResource(Res.string.diag_bench_load, r.loadMs.entries.joinToString(" ") { "${it.key} ${it.value}ms" }), style = SceneTheme.type.footnote)
                        SceneText(stringResource(Res.string.diag_bench_memory, (r.memBeforeLoadBytes / 1_048_576).toInt(), (r.memAfterLoadBytes / 1_048_576).toInt()), style = SceneTheme.type.footnote)
                        SceneText(stringResource(Res.string.diag_bench_streaming, fmt(r.streamingRtf), r.streamingWallMs.toInt(), r.audioMs.toInt(), (r.streamingFirstPartialMs ?: "—").toString()), style = SceneTheme.type.footnote)
                        SceneText(stringResource(Res.string.diag_bench_streaming_text, r.streamingText), style = SceneTheme.type.footnote)
                        r.senseVoiceRtf?.let { SceneText("SenseVoice RTF ${fmt(it)} (${r.senseVoiceWallMs} ms) · ${r.senseVoiceLang}: ${r.senseVoiceText}", style = SceneTheme.type.footnote) }
                        r.sichuanRtf?.let { SceneText("Paraformer RTF ${fmt(it)} (${r.sichuanWallMs} ms): ${r.sichuanText}", style = SceneTheme.type.footnote) }
                        SceneText(stringResource(Res.string.diag_bench_written), style = SceneTheme.type.caption1, color = c.secondaryLabel)
                    }
                    is BenchUi.Error -> SceneText(b.msg.string(), style = SceneTheme.type.footnote, color = c.destructive)
                    else -> Unit
                }
            }
        }
    }
}

private fun fmt(v: Double): String = "${(v * 100).toInt() / 100.0}"

/** 电平表：-60 … 0 dB 映射到 0 … 100%；胶囊轨道用 fill，进度用 tint，> -6 dB（接近削波）用录音红。 */
@Composable
private fun Meter(db: Float) {
    val c = SceneTheme.colors
    val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(c.fill)) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (db > -6f) c.recording else c.tint))
    }
}

private fun fmtDb(v: Float): String = if (v <= -119f) "—" else "${(v * 10).toInt() / 10f} dB"
