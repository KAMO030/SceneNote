package dev.scenenote.ui.glossary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.db.Correction
import dev.scenenote.core.db.GlossaryRepository
import dev.scenenote.core.db.GlossaryTerm
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.ui.common.InlineField
import dev.scenenote.ui.common.SmallPill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** 词袋（用户看到的名字）：general 兜底。 */
internal data class Bucket(val id: String, val label: String)
internal val BUCKETS = listOf(Bucket("general", "通用"), Bucket("travel", "出行"), Bucket("work", "工作"))
internal fun bucketLabel(id: String): String = BUCKETS.firstOrNull { it.id == id }?.label ?: id

data class GlossaryUiState(
    val bucket: String = "general",
    val terms: List<GlossaryTerm> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val myLang: String = "zh-CN", val otherLang: String = "en",
)

class GlossaryViewModel(private val glossary: GlossaryRepository, private val settings: AppSettings) : ViewModel() {
    private val _bucket = MutableStateFlow("general")
    private val _corrections = MutableStateFlow<List<Correction>>(emptyList())
    val ui: StateFlow<GlossaryUiState> = combine(glossary.all(), _bucket, _corrections) { all, b, corr ->
        GlossaryUiState(bucket = b, terms = all.filter { it.bucket == b }.sortedWith(compareByDescending<GlossaryTerm> { it.hits }.thenByDescending { it.updatedAt }),
            corrections = corr.filter { it.bucket == b }, myLang = settings.myLang, otherLang = settings.otherLang)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlossaryUiState())

    init { reloadCorrections() }
    private fun reloadCorrections() { viewModelScope.launch { _corrections.value = glossary.corrections(_bucket.value).distinctBy { it.wrong to it.bucket } } }

    fun setBucket(id: String) { _bucket.value = id; reloadCorrections() }
    /** 新增 / 覆盖：term 为我说的词，translation 为对方语言的译名（对方语言 → 我方向由同一条反查）。 */
    fun add(term: String, translation: String) {
        val t = term.trim(); val tr = translation.trim()
        if (t.isEmpty() || tr.isEmpty()) return
        viewModelScope.launch { glossary.upsert(t, _bucket.value, mapOf(settings.otherLang to tr, settings.myLang to t)) }
    }
    fun delete(t: GlossaryTerm) { viewModelScope.launch { glossary.delete(t.term, t.bucket) } }
    fun addCorrection(wrong: String, right: String) {
        val w = wrong.trim(); val r = right.trim()
        if (w.isEmpty() || r.isEmpty() || w == r) return
        viewModelScope.launch { glossary.addCorrection(w, r, _bucket.value); reloadCorrections() }
    }
    fun deleteCorrection(c: Correction) { viewModelScope.launch { glossary.deleteCorrection(c.wrong, c.bucket); reloadCorrections() } }
}

/**
 * 术语表（I7）：按词袋分段；每条 = 我说的词 → 对方语言译名；实时翻译会优先用这里的译名。
 * 下半段「听错纠正」：识别常听错的词 → 应该是什么（进快路径前置替换）。所有增删都在页内，不弹窗。
 */
@Composable
fun GlossaryScreen(onBack: () -> Unit, vm: GlossaryViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    var term by remember { mutableStateOf("") }
    var translation by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }
    GlassScaffold(
        background = c.groupedBackground,
        topBar = { SceneNavBar(title = "术语表", onBack = onBack) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 104.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            item {
                Box(Modifier.padding(horizontal = SceneSpacing.page)) {
                    SceneSegmentedControl(
                        options = BUCKETS.map { it.label },
                        selectedIndex = BUCKETS.indexOfFirst { it.id == ui.bucket }.coerceAtLeast(0),
                        onSelect = { vm.setBucket(BUCKETS[it].id) },
                    )
                }
            }
            item {
                Column {
                    SceneSectionHeader("译名")
                    SceneGroup {
                        Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InlineField(term, { term = it }, Lang.displayName(ui.myLang), Modifier.weight(1f), imeAction = ImeAction.Next)
                            SceneText("→", style = SceneTheme.type.body, color = c.tertiaryLabel)
                            InlineField(translation, { translation = it }, Lang.displayName(ui.otherLang), Modifier.weight(1f), onDone = { vm.add(term, translation); term = ""; translation = "" })
                            SmallPill("加入", onClick = { vm.add(term, translation); term = ""; translation = "" }, tinted = true, enabled = term.isNotBlank() && translation.isNotBlank())
                        }
                        ui.terms.forEach { t ->
                            SceneDivider(inset = SceneSpacing.row)
                            TermRow(t, ui.otherLang, ui.myLang, onDelete = { vm.delete(t) })
                        }
                    }
                    SceneSectionFooter(if (ui.terms.isEmpty()) "对话结束后的「新词」确认也会进这里" else "翻译时优先用这里的译名")
                }
            }
            item {
                Column {
                    SceneSectionHeader("听错纠正")
                    SceneGroup {
                        Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InlineField(wrong, { wrong = it }, "常听成", Modifier.weight(1f), imeAction = ImeAction.Next)
                            SceneText("→", style = SceneTheme.type.body, color = c.tertiaryLabel)
                            InlineField(right, { right = it }, "应该是", Modifier.weight(1f), onDone = { vm.addCorrection(wrong, right); wrong = ""; right = "" })
                            SmallPill("加入", onClick = { vm.addCorrection(wrong, right); wrong = ""; right = "" }, tinted = true, enabled = wrong.isNotBlank() && right.isNotBlank())
                        }
                        ui.corrections.forEach { cr ->
                            SceneDivider(inset = SceneSpacing.row)
                            Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SceneText("${cr.wrong} → ${cr.right}", Modifier.weight(1f), style = SceneTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SmallPill("删除", onClick = { vm.deleteCorrection(cr) }, destructive = true)
                            }
                        }
                    }
                    SceneSectionFooter("识别到左边的词时自动改成右边")
                }
            }
        }
    }
}

@Composable
private fun TermRow(t: GlossaryTerm, otherLang: String, myLang: String, onDelete: () -> Unit) {
    val c = SceneTheme.colors
    val target = t.translations[otherLang] ?: t.translations.entries.firstOrNull { it.key != myLang && it.value != t.term }?.value ?: ""
    Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            SceneText(t.term, style = SceneTheme.type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SceneText(target.ifBlank { "还没有译名" } + if (t.hits > 0) " · 用过 ${t.hits} 次" else "", style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SmallPill("删除", onClick = onDelete, destructive = true)
    }
}
