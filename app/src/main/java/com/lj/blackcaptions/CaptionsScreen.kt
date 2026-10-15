package com.lj.blackcaptions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Dim = Color(0xFF7A7A7A)
private val Panel = Color(0xFF111111)
private val Red = Color(0xFFFF6B6B)

@Composable
fun CaptionsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Panel, onSurfaceVariant = Dim,
            surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
            outline = Dim,
        ),
    ) {
        // Text without an explicit colour defaults to this; otherwise it'd be black on black.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionsScreen(onStart: () -> Unit) {
    val ctx = LocalContext.current
    val running by Captions.running.collectAsState()
    val paused by Captions.paused.collectAsState()
    val error by Captions.error.collectAsState()
    val models by Models.list.collectAsState()
    val listFailed by Models.listFailed.collectAsState()
    val installed by Models.installed.collectAsState()
    val progress by Models.progress.collectAsState()
    val historyVersion by History.changed.collectAsState()

    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var language by remember { mutableStateOf(Prefs.language(ctx)) }
    var sourceLang by remember { mutableStateOf(Prefs.lang(ctx)) }
    var target by remember { mutableStateOf(Prefs.target(ctx)) }
    var size by remember { mutableStateOf(Prefs.textSize(ctx)) }
    var shade by remember { mutableStateOf(Prefs.shade(ctx)) }
    var accurate by remember { mutableStateOf(Prefs.accurate(ctx)) }
    var pickModel by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Models.refresh(ctx) }
    val history by produceState(emptyList<Line>(), historyVersion) {
        value = withContext(Dispatchers.IO) { History.load(ctx) }
    }

    val ready = model != null && model in installed
    // Open the language list straight away on first run.
    LaunchedEffect(ready, installed) { if (!ready && model == null) pickModel = true }

    fun select(m: VoskModel) {
        Prefs.setModel(ctx, m)
        model = m.name
        language = m.language
        sourceLang = m.lang
        pickModel = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Black Captions", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                running && paused -> "Paused"
                running -> "Captioning what's playing on this phone"
                ready -> "Shows live captions for the sound playing on this phone: videos, podcasts, streams."
                else -> "Pick a speech language and download it to begin."
            },
            color = Dim
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = running || ready,
                onClick = { if (running) CaptionService.stop(ctx) else onStart() }
            ) { Text(if (running) "Stop" else "Start") }
            if (running) {
                OutlinedButton(onClick = { CaptionService.pause(ctx) }) { Text(if (paused) "Resume" else "Pause") }
            }
        }
        Spacer(Modifier.height(20.dp))

        // Speech language
        Picker("Speech language", language?.takeIf { ready } ?: "Choose…", pickModel) { pickModel = !pickModel }
        if (pickModel) {
            if (running) Text("Stop the captions to change the language.", color = Dim, fontSize = 13.sp)
            if (models.isEmpty()) {
                if (listFailed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load the language list. Check the connection.", color = Red, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { Models.refresh(ctx) }) { Text("Retry") }
                    }
                } else {
                    Text("Loading languages…", color = Dim, fontSize = 13.sp)
                }
            }
            models.forEach { m ->
                val have = m.name in installed
                val pct = progress[m.name]
                val selected = m.name == model && have
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !running && pct == null) {
                            if (have) select(m) else Models.download(ctx, m) { select(m) }
                        }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        m.language,
                        color = if (have) Color.White else Dim,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    )
                    Text(
                        when {
                            pct == -1 -> "Unpacking…"
                            pct != null -> "$pct%"
                            selected -> "Selected"
                            have -> "Downloaded"
                            else -> "↓ ${m.size}"
                        },
                        color = Dim, fontSize = 13.sp
                    )
                    if (have && !selected && !running) {
                        TextButton(onClick = { Models.delete(ctx, m.name) }) { Text("Delete", color = Dim, fontSize = 13.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Accurate mode, for languages that have a bigger model
        Models.accurateFor(sourceLang)?.takeIf { ready }?.let { m ->
            val have = m.id in installed
            val pct = progress[m.id]
            Setting("Accurate mode · ${m.label}") {
                when {
                    pct != null -> Text("Downloading… $pct%", color = Dim, modifier = Modifier.padding(12.dp))
                    !have -> TextButton(onClick = { Models.download(ctx, m) }) { Text("Download ${m.size}") }
                    else -> {
                        Choice("On", accurate) { accurate = true; Prefs.setAccurate(ctx, true) }
                        Choice("Off", !accurate) { accurate = false; Prefs.setAccurate(ctx, false) }
                        if (!running) {
                            TextButton(onClick = { Models.delete(ctx, m.id) }) { Text("Delete", color = Dim) }
                        }
                    }
                }
            }
            Text(
                "Live text stays as it is now; when a phrase ends, ${m.label} rewrites it with much better accuracy, " +
                    "punctuation and capitals, usually within a second or two." +
                    if (running && have) " Takes effect the next time you start." else "",
                color = Dim, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
        }

        // Translation
        val source = sourceLang?.let(Translation::fromVosk)
        Picker("Translate to", target?.let(Translation::name) ?: "Off", pickTarget) { pickTarget = !pickTarget }
        if (sourceLang != null && source == null) {
            Text("Translation isn't available from $language.", color = Dim, fontSize = 13.sp)
        }
        if (pickTarget) {
            Text("Each language downloads once (~30 MB), then works offline.", color = Dim, fontSize = 13.sp)
            FlowRow {
                (listOf<String?>(null) + Translation.languages.filter { it != source }).forEach { code ->
                    Choice(code?.let(Translation::name) ?: "Off", code == target) {
                        Prefs.setTarget(ctx, code)
                        target = code
                        pickTarget = false
                        CaptionService.retranslate(ctx)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Setting("Text size") {
            TextSize.entries.forEach {
                Choice(it.label, it == size) {
                    size = it
                    Prefs.setTextSize(ctx, it)
                    CaptionService.restyle(ctx)
                }
            }
        }
        Setting("Background") {
            Shade.entries.forEach {
                Choice(it.label, it == shade) {
                    shade = it
                    Prefs.setShade(ctx, it)
                    CaptionService.restyle(ctx)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = { copy(ctx, history.joinToString("\n") { it.asText() }) }) { Text("Copy all") }
                TextButton(onClick = { History.clear(ctx) }) { Text("Clear", color = Dim) }
            }
        }
        if (history.isEmpty()) {
            Text("Finished captions are saved here, on this phone only.", color = Dim, fontSize = 13.sp)
        }
        history.asReversed().take(100).forEach { line ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { copy(ctx, line.translation ?: line.text) }
                    .padding(vertical = 6.dp)
            ) {
                Text(stamp(line.time), color = Dim, fontSize = 11.sp)
                Text(line.translation ?: line.text)
                if (line.translation != null) Text(line.text, color = Dim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))

        Text(
            "Android asks for screen-recording permission every time you start. Only the sound is used; " +
                "nothing leaves the phone. Some apps block capture (often DRM video), and phone calls can't be captioned.\n\n" +
                "Drag the caption window by its top bar and resize it from the bottom-right corner.",
            color = Dim, fontSize = 13.sp
        )
    }
}

@Composable
private fun Picker(title: String, value: String, open: Boolean, onToggle: () -> Unit) {
    Text(title, color = Dim, fontSize = 13.sp)
    TextButton(onClick = onToggle) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(if (open) "  ▴" else "  ▾", color = Dim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setting(title: String, choices: @Composable () -> Unit) {
    Text(title, color = Dim, fontSize = 13.sp)
    FlowRow { choices() }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) Color.White else Dim,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun stamp(time: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(time))

private fun Line.asText() = buildString {
    append(stamp(time)).append("  ").append(text)
    translation?.let { append("\n    ").append(it) }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Black Captions", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
