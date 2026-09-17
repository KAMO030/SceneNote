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

class AudioSelfTestViewModel(
    audio: AudioFactory, private val appPaths: AppPaths, private val bench: AsrBench,
    private val ttsRouter: dev.scenenote.tts.TtsRouter, private val queue: dev.scenenote.live.PlaybackQueue, private val settings: dev.scenenote.core.settings.AppSettings,
    private val systemTts: dev.scenenote.tts.SystemTtsProvider,
    val probe: dev.scenenote.bench.LatencyProbe,
) : ViewModel() {
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
            if (!zh.available && !en.available) { _tts.value = "无可用语音：${zh.reason}"; return@launch }
            _tts.value = "合成中…（中文：${zh.engine?.id ?: zh.reason}；英文：${en.engine?.id ?: en.reason}）"
            val results = mutableListOf<String>()
            val job = launch {
                queue.events.collect { ev ->
                    when (ev) {
                        is dev.scenenote.live.PlaybackEvent.Started -> results += "${ev.utteranceId} 首音 ${ev.firstChunkMs} ms"
                        is dev.scenenote.live.PlaybackEvent.Done -> results += "${ev.utteranceId} 完成 ${ev.stats.totalMs} ms · ${ev.stats.samples / 16} ms 音频 · ${ev.stats.engineId}"
                        is dev.scenenote.live.PlaybackEvent.Failed -> results += "${ev.utteranceId} 失败：${ev.reason}"
                        is dev.scenenote.live.PlaybackEvent.Skipped -> results += "${ev.utteranceId} 跳过：${ev.reason}"
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
        val pcm = test.lastPcm() ?: run { _bench.value = BenchUi.Error("先录一段 ≥ 3 秒的语音"); return }
        runBenchPcm(pcm)
    }
    /** 用 WAV 文件跑基准（验收脚本：scenenote://selftest?bench=<路径或 bench 目录内文件名>）。 */
    fun runBenchFile(path: String) {
        _bench.value = BenchUi.Running
        viewModelScope.launch {
            _bench.value = runCatching {
                val full = if (path.startsWith("/")) path else appPaths.join(appPaths.benchDir, path)
                BenchUi.Done(bench.run(dev.scenenote.bench.WavIo.readPcm16k(full)))
            }.getOrElse { BenchUi.Error(it.message ?: it.toString()) }
        }
    }
    private fun runBenchPcm(pcm: ShortArray) {
        _bench.value = BenchUi.Running
        viewModelScope.launch { _bench.value = runCatching { BenchUi.Done(bench.run(pcm)) }.getOrElse { BenchUi.Error(it.message ?: it.toString()) } }
    }
    override fun onCleared() { test.release() }
}

sealed interface BenchUi { data object Idle : BenchUi; data object Running : BenchUi; data class Done(val r: AsrBenchResult) : BenchUi; data class Error(val msg: String) : BenchUi }

/** 设置 → 诊断（给开发者 / 反馈用）：录音 / 朗读 / 延迟 / 识别基准四张卡，数据保留，说明各一行。 */
@Composable
fun AudioSelfTestScreen(onBack: () -> Unit, autostart: Boolean = false, stopAfterSec: Int = 0, benchFile: String? = null, ttsTest: Boolean = false, vm: AudioSelfTestViewModel = koinViewModel()) {
    val c = SceneTheme.colors
    val s by vm.test.state.collectAsState()
    val bench by vm.benchUi.collectAsState()
    val tts by vm.ttsUi.collectAsState()
    val lat by vm.latency.collectAsState()
    var ran by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {   // 深链参数只执行一次（重建不重复跑基准 / 朗读）
        if (ran) return@LaunchedEffect
        ran = true
        if (!benchFile.isNullOrBlank()) vm.runBenchFile(benchFile)
        if (ttsTest) vm.speakTest()
    }
    LaunchedEffect(autostart, stopAfterSec) {
        if (autostart) {
            vm.test.start()
            if (stopAfterSec > 0) { kotlinx.coroutines.delay(stopAfterSec * 1000L); vm.test.stop() }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.test.stop() } }
    GlassScaffold(topBar = { SceneNavBar(title = "诊断", onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 104.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            // 录音：电平 + 路由 + 录音 / 测试音
            SceneCard {
                SceneText("录音", style = SceneTheme.type.headline)
                SceneText("说话时电平会动，停止后自动保存", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                Meter(s.rmsDb)
                SceneText("RMS ${fmtDb(s.rmsDb)}   峰值 ${fmtDb(s.peakDb)}   帧 ${s.frames}", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                s.route?.let { r -> SceneText("输入 ${r.input} · 输出 ${r.output}", style = SceneTheme.type.footnote, color = c.secondaryLabel) }
                Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
                    if (!s.recording) SceneButton("开始录音", onClick = { vm.test.start() }, style = ButtonStyle.Prominent, icon = SceneIcons.Mic, modifier = Modifier.weight(1f))
                    else SceneButton("停止", onClick = { vm.test.stop() }, style = ButtonStyle.Prominent, icon = SceneIcons.Stop, modifier = Modifier.weight(1f))
                    SceneButton(if (s.tonePlaying) "播放中" else "测试音", onClick = { vm.test.playTone() }, style = ButtonStyle.Gray, enabled = !s.tonePlaying, icon = SceneIcons.Speaker)
                }
                s.lastFile?.let { SceneText("已保存 $it（${s.lastFileBytes / 1024} KB）", style = SceneTheme.type.caption1, color = c.secondaryLabel) }
                if (autostart && stopAfterSec > 0) SceneText("自动模式：${stopAfterSec} s 后停止", style = SceneTheme.type.caption1, color = c.secondaryLabel)
                s.error?.let { SceneText(it, style = SceneTheme.type.footnote, color = c.destructive) }
            }

            // 朗读：中英各一句
            SceneCard {
                SceneText("朗读", style = SceneTheme.type.headline)
                SceneText("中英各一句，从当前输出设备播放", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                SceneButton("朗读测试", onClick = { vm.speakTest() }, style = ButtonStyle.Tinted, icon = SceneIcons.Headphones, modifier = Modifier.fillMaxWidth())
                if (tts.isNotBlank()) SceneText(tts, style = SceneTheme.type.footnote)
            }

            // 延迟：来自实时会话的 LatencyProbe
            SceneCard {
                SceneText("延迟", style = SceneTheme.type.headline)
                SceneText("每句说完到听见译文的时间", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                if (lat.count == 0) SceneText("还没有数据，跑一段会话后回来看", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                else {
                    val ok = (lat.e2eP50 ?: Long.MAX_VALUE) <= 1300 && (lat.e2eP95 ?: Long.MAX_VALUE) <= 2000
                    SceneText("${lat.count} 句 · P50 ${lat.e2eP50 ?: "—"} ms · P95 ${lat.e2eP95 ?: "—"} ms · ${if (ok) "达标" else "未达标"}", style = SceneTheme.type.footnote, color = if (ok) c.tint else c.destructive)
                    SceneText("识别 ${lat.asrP50 ?: "—"} · 翻译 ${lat.mtP50 ?: "—"} · 语音 ${lat.ttsP50 ?: "—"} ms", style = SceneTheme.type.footnote)
                    SceneButton("清空", onClick = { vm.clearLatency() }, style = ButtonStyle.Gray, modifier = Modifier.fillMaxWidth())
                }
            }

            // 识别基准：用上一段录音跑本机识别
            SceneCard {
                SceneText("识别基准", style = SceneTheme.type.headline)
                SceneText("用上一段录音跑本机识别，需先下载语音包", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                SceneButton(
                    if (bench is BenchUi.Running) "跑分中" else "跑基准 · 已录 ${s.recordedSec} s",
                    onClick = { vm.runBench() }, style = ButtonStyle.Tinted,
                    enabled = !s.recording && s.recordedSec >= 3 && bench !is BenchUi.Running,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val b = bench) {
                    is BenchUi.Done -> {
                        val r = b.r
                        SceneText("装载 " + r.loadMs.entries.joinToString(" ") { "${it.key} ${it.value}ms" }, style = SceneTheme.type.footnote)
                        SceneText("内存 ${r.memBeforeLoadBytes / 1_048_576} → ${r.memAfterLoadBytes / 1_048_576} MB", style = SceneTheme.type.footnote)
                        SceneText("流式 RTF ${fmt(r.streamingRtf)}（${r.streamingWallMs} ms / ${r.audioMs} ms）· 首包 ${r.streamingFirstPartialMs ?: "—"} ms", style = SceneTheme.type.footnote)
                        SceneText("流式结果：${r.streamingText}", style = SceneTheme.type.footnote)
                        r.senseVoiceRtf?.let { SceneText("SenseVoice RTF ${fmt(it)}（${r.senseVoiceWallMs} ms）· ${r.senseVoiceLang}：${r.senseVoiceText}", style = SceneTheme.type.footnote) }
                        r.sichuanRtf?.let { SceneText("川渝 Paraformer RTF ${fmt(it)}（${r.sichuanWallMs} ms）：${r.sichuanText}", style = SceneTheme.type.footnote) }
                        SceneText("已写入 bench/asr-bench.jsonl", style = SceneTheme.type.caption1, color = c.secondaryLabel)
                    }
                    is BenchUi.Error -> SceneText(b.msg, style = SceneTheme.type.footnote, color = c.destructive)
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
