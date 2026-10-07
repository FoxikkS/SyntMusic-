package com.syntmusic.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.syntmusic.ui.Pill
import com.syntmusic.ui.theme.Palette
import com.syntmusic.ui.theme.SyntTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val swatches = listOf(
    0xFF171717, 0xFF000000, 0xFF2B2D42, 0xFF1E3A5F, 0xFF14532D,
    0xFF4A1D1F, 0xFF5B21B6, 0xFFB45309, 0xFFE11D48, 0xFFF2F2F2,
).map { it.toInt() }

/** Shown when a widget is added (and on reconfigure): background, color and opacity, with a live preview. */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val manager = AppWidgetManager.getInstance(this)
        val kind = WidgetKind.of(manager.getAppWidgetInfo(widgetId)?.provider?.className) ?: WidgetKind.MEDIUM

        setContent {
            SyntTheme {
                WidgetConfigScreen(
                    kind = kind,
                    initial = WidgetSettings.load(this, widgetId),
                    onSave = { style ->
                        WidgetSettings.save(this, widgetId, style)
                        WidgetRenderer.scope.launch {
                            WidgetRenderer.update(this@WidgetConfigActivity, manager, kind, widgetId)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(kind: WidgetKind, initial: WidgetStyle, onSave: (WidgetStyle) -> Unit) {
    val context = LocalContext.current
    var style by remember { mutableStateOf(initial) }
    val preview by produceState<RemoteViews?>(null, style) { value = WidgetRenderer.build(context, kind, style) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .systemBarsPadding()
            .padding(20.dp),
    ) {
        Text("Widget style", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // Checkerboard behind the preview makes transparency visible.
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(20.dp))
                .drawBehind {
                    val cell = 12.dp.toPx()
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = 0f
                        var col = 0
                        while (x < size.width) {
                            drawRect(
                                if ((row + col) % 2 == 0) Color(0xFF2A2A2A) else Color(0xFF1F1F1F),
                                Offset(x, y),
                                Size(cell, cell),
                            )
                            x += cell
                            col++
                        }
                        y += cell
                        row++
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { views ->
                AndroidView(
                    factory = { FrameLayout(it) },
                    update = { frame ->
                        frame.removeAllViews()
                        frame.addView(views.apply(frame.context, frame))
                    },
                    modifier = when (kind) {
                        WidgetKind.MINI -> Modifier.size(width = 170.dp, height = 58.dp)
                        WidgetKind.SMALL -> Modifier.size(160.dp)
                        WidgetKind.MEDIUM -> Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(76.dp)
                        WidgetKind.LARGE -> Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(150.dp)
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Background", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WidgetBackground.entries) { option ->
                Pill(
                    text = when (option) {
                        WidgetBackground.ARTWORK -> "Cover color"
                        WidgetBackground.DARK -> "Dark"
                        WidgetBackground.LIGHT -> "Light"
                        WidgetBackground.CUSTOM -> "Custom"
                    },
                    selected = style.background == option,
                    onClick = { style = style.copy(background = option) },
                )
            }
        }

        if (style.background == WidgetBackground.CUSTOM) {
            Spacer(Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(swatches) { color ->
                    val selected = style.customColor == color
                    Box(
                        Modifier
                            .size(38.dp)
                            .border(2.dp, if (selected) Palette.Primary else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable { style = style.copy(customColor = color) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${style.opacity}%", color = Palette.Secondary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = style.opacity / 100f,
            onValueChange = { style = style.copy(opacity = (it * 100).roundToInt()) },
            colors = SliderDefaults.colors(
                thumbColor = Palette.Primary,
                activeTrackColor = Palette.Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )

        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Palette.Primary)
                .clickable { onSave(style) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Save", color = Palette.Background, style = MaterialTheme.typography.titleMedium)
        }
    }
}
