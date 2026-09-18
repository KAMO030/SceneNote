package dev.scenenote.core.platform

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.scenenote.core.i18n.UiLocale
import java.util.Locale

/**
 * Android 界面语言覆盖。不重建 Activity（会黑一下、丢状态）：改完进程默认 locale 后 [UiLocale.bump]，Compose 原地重组换文案。
 * - 13+：交给 [LocaleManager]（系统设置里的「应用语言」同步可见）；MainActivity 声明 configChanges=locale 自己接配置变化，
 *   在 [onActivityConfigurationChanged] 里更新进程默认 locale 并 bump。
 * - 26–32：写自己的 prefs，进程内 [Locale.setDefault] / [LocaleList.setDefault]（Compose 资源与 CMP getString 都从这里取），
 *   应用级 Resources 也套上（通知 / 磁贴 / 小组件用 context.getString），Activity 经 [wrap] 包一层配置。
 */
class AndroidAppLocale(private val app: Application) : AppLocale {

    override val appliesImmediately: Boolean = true

    override val override: String?
        get() = if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()?.substringBefore('-')
        } else stored(app)

    override fun set(tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(app).edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.apply()
        applyToProcess(app)
    }

    companion object {
        private const val PREFS = "ui_locale"
        private const val KEY = "tag"
        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun stored(ctx: Context): String? = if (Build.VERSION.SDK_INT >= 33) null else prefs(ctx).getString(KEY, null)

        /** 系统语言列表（覆盖前记下来，切回「跟随系统」时用）；Application.onConfigurationChanged 时随系统更新。 */
        private var system: LocaleList? = null

        /**
         * Application.onCreate / onConfigurationChanged / [set] 时调用：把已保存的覆盖（或系统语言）套到进程默认 locale 与应用级 Resources 上。
         * 13+ 由系统处理，这里不动。
         */
        @Suppress("DEPRECATION")
        fun applyToProcess(app: Application, systemLocales: LocaleList? = null) {
            if (Build.VERSION.SDK_INT >= 33) return
            if (systemLocales != null) system = systemLocales else if (system == null) system = LocaleList.getDefault()
            val target = stored(app)?.let { LocaleList(Locale.forLanguageTag(it)) } ?: system!!
            if (target.isEmpty) return
            Locale.setDefault(target[0])
            LocaleList.setDefault(target)
            val res = app.resources
            val cfg = Configuration(res.configuration).apply { setLocales(target) }
            res.updateConfiguration(cfg, res.displayMetrics)
            UiLocale.bump()
        }

        /** MainActivity.onConfigurationChanged：13+ 的按应用语言由系统送来新配置，这里把进程默认 locale 跟上再重组；更早版本按覆盖优先重套。 */
        fun onActivityConfigurationChanged(app: Application, newConfig: Configuration) {
            if (Build.VERSION.SDK_INT >= 33) {
                val locales = newConfig.locales
                if (!locales.isEmpty) { Locale.setDefault(locales[0]); LocaleList.setDefault(locales) }
                UiLocale.bump()
            } else applyToProcess(app, newConfig.locales)
        }

        /** Activity.attachBaseContext：有覆盖时给 Activity 一份带该 locale 的配置（Android 资源用）。 */
        fun wrap(base: Context): Context {
            val tag = stored(base) ?: return base
            val cfg = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
            return base.createConfigurationContext(cfg)
        }
    }
}
