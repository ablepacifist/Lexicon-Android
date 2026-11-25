package com.alexdyakin.lexicon.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.AppVersionInfo
import com.alexdyakin.lexicon.ui.components.LaunchedWhileResumed
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.profile.AppUpdateManager
import com.alexdyakin.lexicon.ui.theme.LexGround
import com.alexdyakin.lexicon.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class RequiredUpdateState(
    /** Set when the server marks a newer build critical; the app is covered until it is installed. */
    val required: AppVersionInfo? = null,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val apk: File? = null,
    val message: String? = null,
)

/**
 * Makes `APP_UPDATE_CRITICAL=true` mean something.
 *
 * The flag reached the app only as a " - required" label on the Profile screen, after the
 * player had opened Profile and tapped "Check for updates". Nobody else ever saw it, so a
 * server change that needed the new build (Alchemy's SSO check, for one) could only be
 * switched on once every tester happened to update by themselves. Now the app checks on
 * every return to the foreground and, for a critical build, covers itself until it is
 * installed. A non-critical update is still only offered on Profile.
 */
@HiltViewModel
class RequiredUpdateViewModel @Inject constructor(
    private val updates: AppUpdateManager,
) : ViewModel() {
    private val _state = MutableStateFlow(RequiredUpdateState())
    val state: StateFlow<RequiredUpdateState> = _state.asStateFlow()

    fun check() {
        if (_state.value.downloading) return
        viewModelScope.launch {
            // A failed check never blocks: offline must not lock anyone out of the app.
            val latest = (updates.fetchLatestVersion() as? ApiResult.Success)?.data ?: return@launch
            val required = latest.takeIf { it.critical && it.versionCode > updates.currentVersionCode() }
            _state.update { it.copy(required = required) }
        }
    }

    fun download() {
        val info = _state.value.required ?: return
        viewModelScope.launch {
            _state.update { it.copy(downloading = true, progress = 0, message = null) }
            updates.downloadApk(info.downloadUrl, info.sha256) { p -> _state.update { it.copy(progress = p) } }
                .onSuccess { apk -> _state.update { it.copy(downloading = false, apk = apk) } }
                .onFailure {
                    _state.update {
                        it.copy(downloading = false, progress = null, message = "The download didn't finish. Check your connection and try again.")
                    }
                }
        }
    }

    fun canInstall(): Boolean = updates.canRequestPackageInstalls()

    fun install() {
        val apk = _state.value.apk?.takeIf { it.exists() } ?: run {
            _state.update { it.copy(apk = null, message = "Download the update again.") }
            return
        }
        updates.launchInstaller(apk).onFailure {
            _state.update { s -> s.copy(message = "Couldn't open the installer.") }
        }
    }

    fun installPermissionMissing() =
        _state.update { it.copy(message = "Allow installs from Lexicon, then tap Install again.") }
}

/** Draws [content], covered by the update screen while a critical update is outstanding. */
@Composable
fun RequiredUpdateGate(
    viewModel: RequiredUpdateViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedWhileResumed(Unit) { viewModel.check() }

    Box(Modifier.fillMaxSize()) {
        content()
        state.required?.let { info -> RequiredUpdateScreen(info, state, viewModel) }
    }
}

@Composable
private fun RequiredUpdateScreen(info: AppVersionInfo, state: RequiredUpdateState, viewModel: RequiredUpdateViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.canInstall()) viewModel.install() else viewModel.installPermissionMissing()
    }
    // A full-screen surface, so taps cannot reach the app beneath it.
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(Modifier.fillMaxSize().background(LexGround))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Update required", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "This version no longer works with the servers. Install version ${info.versionName} to keep using Lexicon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            info.changelog.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (state.downloading) {
                val p = state.progress
                if (p != null) LinearProgressIndicator(progress = { p / 100f }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            when {
                state.apk != null -> LexButton("Install", onClick = {
                    if (viewModel.canInstall()) {
                        viewModel.install()
                    } else {
                        permission.launch(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }, tone = LexTone.Primary)
                else -> LexButton(
                    if (state.downloading) "Downloading…" else "Download update",
                    onClick = viewModel::download,
                    tone = LexTone.Primary,
                    enabled = !state.downloading,
                    loading = state.downloading,
                )
            }
        }
    }
}
