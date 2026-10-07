package com.syntmusic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.syntmusic.MusicApp
import kotlinx.coroutines.launch

abstract class SyntWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val pending = goAsync()
        WidgetRenderer.scope.launch {
            try {
                widgetIds.forEach { WidgetRenderer.update(context, manager, kind, it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        widgetIds.forEach { WidgetSettings.delete(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val player = (context.applicationContext as MusicApp).container.player
        when (intent.action) {
            ACTION_PLAY_PAUSE -> player.togglePlayPause()
            ACTION_NEXT -> player.next()
            ACTION_PREVIOUS -> player.previous()
            else -> super.onReceive(context, intent)
        }
    }
}

class MiniWidgetProvider : SyntWidgetProvider(WidgetKind.MINI)
class SmallWidgetProvider : SyntWidgetProvider(WidgetKind.SMALL)
class MediumWidgetProvider : SyntWidgetProvider(WidgetKind.MEDIUM)
class LargeWidgetProvider : SyntWidgetProvider(WidgetKind.LARGE)
