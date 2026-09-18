package dev.scenenote.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.scenenote.app.R

/**
 * 主屏小组件（I7 最小入口，规格 §3.1）：两枚按钮「仅听」「会议」，点击走 scenenote:// 深链一按进场景。
 * 只做入口，不做开关；与快捷设置磁贴同一套深链。
 */
class SceneWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content() } }
    }

    @Composable
    private fun Content() {
        // 底色跟设置里的主题色（浅色 tint，白字 ≥ 4.5:1）；Koin 还没起来时用默认色
        val accent = runCatching { org.koin.core.context.GlobalContext.getOrNull()?.get<dev.scenenote.core.settings.AppSettings>()?.accent?.value }.getOrNull()
        val tint = Color(dev.scenenote.core.designsystem.Accents.byId(accent).light.tint)
        Row(
            GlanceModifier.fillMaxSize().background(tint).cornerRadius(24.dp).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ctx = androidx.glance.LocalContext.current
            Pill(ctx.getString(R.string.widget_listen), "scenenote://scene/listen?autostart=1", GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            Pill(ctx.getString(R.string.widget_meeting), "scenenote://scene/meeting?autostart=1", GlanceModifier.defaultWeight())
        }
    }

    @Composable
    private fun Pill(label: String, link: String, modifier: GlanceModifier) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(androidx.glance.LocalContext.current, DeepLinkActivity::class.java)
        Box(
            modifier.height(44.dp).background(Color(0x33FFFFFF)).cornerRadius(22.dp).clickable(actionStartActivity(intent)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Medium))
        }
    }
}

class SceneWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SceneWidget()
}
