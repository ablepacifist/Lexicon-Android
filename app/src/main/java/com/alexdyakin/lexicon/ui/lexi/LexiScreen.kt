package com.alexdyakin.lexicon.ui.lexi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.lexi.LexiService
import com.alexdyakin.lexicon.lexi.LexiSettings
import com.alexdyakin.lexicon.lexi.LexiSettingsStore
import com.alexdyakin.lexicon.lexi.LexiState
import com.alexdyakin.lexicon.lexi.LexiTurn
import com.alexdyakin.lexicon.ui.components.OnScrimDim
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.components.SectionHeader
import java.text.DateFormat
import java.util.Date

@Composable
fun LexiScreen(onBack: () -> Unit, viewModel: LexiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val wakeScore by viewModel.wakeScore.collectAsState()
    val turns by viewModel.turns.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // Re-checked on every state change, so the note disappears once the model is in place.
    val speechModelReady = remember(state) { viewModel.speechModelReady() }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Notifications are optional: the service runs without them, only invisibly.
        permissionDenied = grants[Manifest.permission.RECORD_AUDIO] != true
        if (!permissionDenied) viewModel.turnOn()
    }

    fun switchOn() {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            permissionDenied = false
            viewModel.turnOn()
        } else {
            val wanted = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions.launch(wanted.toTypedArray())
        }
    }

    ScreenScaffold("Lexi", onBack, R.drawable.bg_dashboard) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    state = state,
                    wakeScore = wakeScore,
                    threshold = settings.wakeThreshold,
                    running = state !is LexiState.Off && state !is LexiState.Failed,
                    onToggle = { on -> if (on) switchOn() else viewModel.turnOff() },
                    onAsk = viewModel::askNow,
                    onCancel = viewModel::cancelTurn,
                    permissionDenied = permissionDenied,
                    downloadNote = if (speechModelReady) null
                    else "Switching Lexi on for the first time downloads its " +
                        "${viewModel.speechModelMb} MB speech model. Best done on Wi-Fi; " +
                        "it is kept afterwards and survives app updates.",
                )
            }

            item {
                AskByTyping(enabled = state == LexiState.Listening, onAsk = viewModel::askText)
            }

            if (turns.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(turns, key = { it.atMillis }) { TurnCard(it) }
            }

            item { SectionHeader("Settings") }
            item {
                TokenCard(
                    settings = settings,
                    onSave = viewModel::saveToken,
                    onRemove = viewModel::removeToken,
                )
            }
            item { SensitivityCard(settings.wakeThreshold, viewModel::setWakeThreshold) }
            item { BrainCard(settings, viewModel::saveBrain) }
            item { BackgroundCard() }
            item {
                Text(
                    "Audio never leaves this phone. The wake word and speech recognition run on the " +
                        "device; only the words of your question are sent to the brain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnScrimDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ── status ───────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(
    state: LexiState,
    wakeScore: Float,
    threshold: Float,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    permissionDenied: Boolean,
    downloadNote: String?,
) {
    Card(colors = panelColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Orb(state)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        headline(state),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        LexiService.describe(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is LexiState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (state as? LexiState.Thinking)?.heard?.let { HeardLine(it) }
                    (state as? LexiState.Speaking)?.heard?.let { HeardLine(it) }
                }
                Switch(checked = running, onCheckedChange = onToggle)
            }

            if (state == LexiState.Listening) {
                Spacer(Modifier.height(14.dp))
                WakeMeter(wakeScore, threshold)
            }

            if (downloadNote != null) {
                Text(
                    downloadNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            if (permissionDenied) {
                Text(
                    "Lexi can't listen without the microphone permission. You can grant it in the app's settings.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAsk, enabled = state == LexiState.Listening) { Text("Ask now") }
                val busy = state == LexiState.Capturing || state is LexiState.Thinking || state is LexiState.Speaking
                OutlinedButton(onClick = onCancel, enabled = busy) { Text("Cancel") }
            }
            Text(
                "Say “Hey Jarvis”, wait for the tone, then ask.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HeardLine(heard: String) {
    Text(
        "“$heard”",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun headline(state: LexiState) = when (state) {
    LexiState.Off -> "Off"
    is LexiState.Starting -> "Starting"
    LexiState.Listening -> "Ready"
    LexiState.Capturing -> "Listening"
    is LexiState.Thinking -> "Thinking"
    is LexiState.Speaking -> "Speaking"
    LexiState.PausedForCall -> "Paused"
    is LexiState.Failed -> "Stopped"
}

/**
 * A glowing orb whose colour and motion say what Lexi is doing at a glance: a slow breath
 * when waiting, a quick pulse when listening to you, a turning arc while it thinks.
 */
@Composable
private fun Orb(state: LexiState) {
    val primary = MaterialTheme.colorScheme.primary
    val target = when (state) {
        LexiState.Capturing -> Color(0xFF7FB069)
        is LexiState.Thinking, is LexiState.Speaking -> Color(0xFF8AB4F8)
        is LexiState.Failed -> MaterialTheme.colorScheme.error
        LexiState.Off, LexiState.PausedForCall -> OnScrimDim.copy(alpha = 0.4f)
        else -> primary
    }
    val color by animateColorAsState(target, label = "orbColor")

    val transition = rememberInfiniteTransition(label = "orb")
    val periodMs = if (state == LexiState.Capturing || state is LexiState.Speaking) 600 else 2400
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "sweep",
    )
    val animated = state == LexiState.Listening || state == LexiState.Capturing ||
        state is LexiState.Thinking || state is LexiState.Speaking || state is LexiState.Starting

    Canvas(Modifier.size(56.dp)) {
        val radius = size.minDimension / 2
        val scale = if (animated) pulse else 0.82f
        drawCircle(color.copy(alpha = 0.22f), radius * scale)
        drawCircle(color, radius * 0.55f * scale)
        if (state is LexiState.Thinking || state is LexiState.Starting) {
            val inset = 3.dp.toPx()
            drawArc(
                color = color,
                startAngle = sweep,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

/** The live wake-word score against the threshold, so sensitivity can be tuned by eye. */
@Composable
private fun WakeMeter(score: Float, threshold: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onSurface
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp),
        ) {
            val corner = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = corner)
            drawRoundRect(fill, size = Size(size.width * score.coerceIn(0f, 1f), size.height), cornerRadius = corner)
            val x = size.width * threshold
            drawLine(marker, Offset(x, -4f), Offset(x, size.height + 4f), strokeWidth = 2.dp.toPx())
        }
        Text(
            "Wake-word score  %.2f  (wakes at %.2f)".format(score, threshold),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── asking ───────────────────────────────────────────────────────────────────────────

@Composable
private fun AskByTyping(enabled: Boolean, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        onAsk(text)
        text = ""
    }
    Card(colors = panelColors()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Or type a question") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::send, enabled = enabled && text.isNotBlank()) { Text("Ask") }
        }
    }
}

@Composable
private fun TurnCard(turn: LexiTurn) {
    Card(colors = panelColors()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(turn.atMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "You: ${turn.heard}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Lexi: ${turn.reply}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (turn.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ── settings ─────────────────────────────────────────────────────────────────────────

@Composable
private fun TokenCard(settings: LexiSettings, onSave: (String) -> Boolean, onRemove: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(colors = panelColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Brain token", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !settings.secureStorageAvailable ->
                        "This phone can't store the token encrypted, so Lexi won't store it at all."
                    settings.hasAgentToken -> "A token is saved. It is stored encrypted and never shown again."
                    else -> "Paste the gateway's agent token. Lexi can't reach the brain without it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                label = { Text(if (settings.hasAgentToken) "Replace token" else "Agent token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = settings.secureStorageAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (onSave(draft)) draft = "" else error = "Couldn't store the token securely."
                    },
                    // The gateway refuses tokens shorter than 32 characters.
                    enabled = draft.trim().length >= MIN_TOKEN_LENGTH,
                ) { Text("Save") }
                if (settings.hasAgentToken) TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private const val MIN_TOKEN_LENGTH = 32

@Composable
private fun SensitivityCard(threshold: Float, onChange: (Float) -> Unit) {
    var value by remember(threshold) { mutableFloatStateOf(threshold) }
    Card(colors = panelColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Wake sensitivity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Threshold %.2f. Lower wakes more easily but also wakes by mistake more often; ".format(value) +
                    "watch the score while you talk to find the right spot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onChange(value) },
                valueRange = LexiSettingsStore.MIN_THRESHOLD..LexiSettingsStore.MAX_THRESHOLD,
            )
        }
    }
}

@Composable
private fun BrainCard(settings: LexiSettings, onSave: (String, String, String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tunnel by remember(settings.tunnelUrl) { mutableStateOf(settings.tunnelUrl) }
    var lan by remember(settings.lanUrl) { mutableStateOf(settings.lanUrl) }
    var orchestrator by remember(settings.orchestrator) { mutableStateOf(settings.orchestrator) }

    Card(colors = panelColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Brain", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        settings.tunnelUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Done" else "Edit") }
            }
            if (expanded) {
                OutlinedTextField(tunnel, { tunnel = it }, label = { Text("Tunnel URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lan, { lan = it }, label = { Text("LAN URL (optional, tried second)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(orchestrator, { orchestrator = it }, label = { Text("Voice model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        onSave(tunnel, lan, orchestrator)
                        expanded = false
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Save") }
            }
        }
    }
}

/**
 * Android may pause a background app's network access to save battery, which would leave
 * Lexi hearing the question but unable to ask the brain.
 */
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isBatteryExempt(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempt = isBatteryExempt(context)
    }
    if (exempt) return

    Card(colors = panelColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Background listening", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Battery optimisation can cut Lexi off from the brain while the screen is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = { launcher.launch(batteryExemptionIntent(context)) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Allow") }
        }
    }
}

@Composable
private fun panelColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
)

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

// Allowed for this app: it is distributed outside the Play Store, which is where the
// policy restricting this intent applies.
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
