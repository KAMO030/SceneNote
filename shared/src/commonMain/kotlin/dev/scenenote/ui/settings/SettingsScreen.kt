package dev.scenenote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.scenenote.core.model.PrivacyMode
import org.koin.compose.viewmodel.koinViewModel

/** 设置页：四档隐私 + Key 钱包（只进 SecureStore）+ 本地消费闸门。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("数据去向", style = MaterialTheme.typography.titleMedium)
                    Text("四档只约束互联网出站；任何出站都记入去向账本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val options = listOf(
                        PrivacyMode.Locked to "仅本机（锁定）",
                        PrivacyMode.LocalWithPerSegmentConsent to "仅本机 + 逐段授权",
                        PrivacyMode.TextOnlyCloud to "仅文本上云",
                        PrivacyMode.AudioCloud to "音频与文本上云",
                    )
                    options.forEach { (mode, label) ->
                        FilterChip(selected = ui.privacy == mode, onClick = { vm.setPrivacy(mode) }, label = { Text(label) })
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Key 钱包", style = MaterialTheme.typography.titleMedium)
                    Text("App 不内置任何 Key；你的 Key 只存在本机安全存储里，直连对应服务商。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.providers.forEach { p -> ProviderEditor(p, onSaveKey = { vm.setKey(p.id, it) }, onSaveBaseUrl = { vm.setBaseUrl(p.id, it) }) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地消费闸门", style = MaterialTheme.typography.titleMedium)
                    var limit by remember(ui.monthlyLimit) { mutableStateOf(if (ui.monthlyLimit == 0.0) "" else ui.monthlyLimit.toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = limit, onValueChange = { limit = it }, label = { Text("每月估算上限（0 = 不限）") }, modifier = Modifier.weight(1f), singleLine = true)
                        Button(onClick = { vm.setMonthlyLimit(limit.toDoubleOrNull() ?: 0.0) }) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditor(p: ProviderRow, onSaveKey: (String) -> Unit, onSaveBaseUrl: (String) -> Unit) {
    var key by remember(p.id) { mutableStateOf("") }
    var base by remember(p.id, p.baseUrl) { mutableStateOf(p.baseUrl ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.name + (p.masked?.let { "  · 已保存 $it" } ?: "  · 未配置"), style = MaterialTheme.typography.labelLarge)
        if (p.needsBaseUrl) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Base URL") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { onSaveBaseUrl(base) }) { Text("保存") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, modifier = Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { onSaveKey(key); key = "" }) { Text(if (p.masked == null) "保存" else "替换") }
        }
        if (p.masked != null) TextButton(onClick = { onSaveKey("") }) { Text("删除 Key") }
    }
}
