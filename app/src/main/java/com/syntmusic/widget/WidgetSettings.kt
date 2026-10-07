package com.syntmusic.widget

import android.content.Context

enum class WidgetBackground { ARTWORK, DARK, LIGHT, CUSTOM }

data class WidgetStyle(
    val background: WidgetBackground = WidgetBackground.ARTWORK,
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    /** 0..100 */
    val opacity: Int = 100,
)

const val DEFAULT_CUSTOM_COLOR = 0xFF1E3A5F.toInt()

/** Per-widget appearance, keyed by app widget id. */
object WidgetSettings {

    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun load(context: Context, widgetId: Int): WidgetStyle {
        val prefs = prefs(context)
        val background = prefs.getString("$widgetId.background", null)
            ?.let { name -> WidgetBackground.entries.firstOrNull { it.name == name } }
            ?: WidgetBackground.ARTWORK
        return WidgetStyle(
            background = background,
            customColor = prefs.getInt("$widgetId.color", DEFAULT_CUSTOM_COLOR),
            opacity = prefs.getInt("$widgetId.opacity", 100),
        )
    }

    fun save(context: Context, widgetId: Int, style: WidgetStyle) {
        prefs(context).edit()
            .putString("$widgetId.background", style.background.name)
            .putInt("$widgetId.color", style.customColor)
            .putInt("$widgetId.opacity", style.opacity)
            .apply()
    }

    fun delete(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove("$widgetId.background")
            .remove("$widgetId.color")
            .remove("$widgetId.opacity")
            .apply()
    }
}
