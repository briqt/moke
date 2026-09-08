package com.briqt.moke

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
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
        Locale.setDefault(Locale.forLanguageTag(tag))
        return localizedContext(context)
    }

    /**
     * 只按应用内语言换一层 Configuration，**不动 JVM 默认 Locale**。
     * 供后台路径按需取字符串用——那里每条消息都调一次，不该反复改全局状态。
     */
    fun localizedContext(context: Context): Context {
        val tag = currentTag(context)
        if (tag.isBlank()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }
}

/**
 * 按**应用内选择的语言**取字符串。
 *
 * 界面本身没问题：Compose 用的是 Activity context，`attachBaseContext` 里已被 [LocaleManager.wrap]
 * 包过。但传输层、前台服务、传输队列手里只有 Application / Service context，它们的 Configuration
 * 跟随的是**系统**语言——于是「系统中文 + 应用内选英文」时，界面是英文，而终端里的连接提示、
 * 通知、传输错误仍然是中文（issue #1）。
 *
 * 每次调用都重新解析而不是缓存一个 context：应用内语言可以随时切换，缓存会留下旧语言的串。
 * 这些都是低频调用（连接提示、错误、通知），这点开销可以忽略。
 */
fun Context.localized(@StringRes id: Int, vararg args: Any): String =
    LocaleManager.localizedContext(this).getString(id, *args)
