package com.briqt.moke

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言切换（i18n）：用独立 SharedPreferences 存语言标签，[wrap] 在 attachBaseContext 时
 * 同步读取并包裹 Configuration。标签空串 = 跟随系统；"zh"/"en" = 强制该语言。切换后 Activity.recreate() 生效。
 * 资源：values（英文，默认/兜底）+ values-zh（中文）。
 */
object LocaleManager {
    private const val PREFS = "moke_locale"
    private const val KEY = "lang_tag"

    const val SYSTEM = ""   // 跟随系统
    const val ZH = "zh"
    const val EN = "en"

    /** 已存的语言标签（"" = 跟随系统）。 */
    fun currentTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** 按存储的语言标签包裹 context（空标签直接返回，跟随系统）。attachBaseContext 调用。 */
    fun wrap(context: Context): Context {
        val tag = currentTag(context)
        if (tag.isBlank()) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
