package com.alexdyakin.lexicon.ui.lexi

import android.content.Context
import androidx.lifecycle.ViewModel
import com.alexdyakin.lexicon.lexi.LexiAssistant
import com.alexdyakin.lexicon.lexi.LexiService
import com.alexdyakin.lexicon.lexi.LexiSettings
import com.alexdyakin.lexicon.lexi.LexiSettingsStore
import com.alexdyakin.lexicon.lexi.LexiState
import com.alexdyakin.lexicon.lexi.LexiTurn
import com.alexdyakin.lexicon.lexi.SpeechModelInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LexiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assistant: LexiAssistant,
    private val settingsStore: LexiSettingsStore,
    private val speechModels: SpeechModelInstaller,
) : ViewModel() {

    /** Megabytes the first switch-on has to download, so the screen can say so up front. */
    val speechModelMb: Int = (speechModels.downloadBytes / 1_000_000).toInt()

    /** Cheap file check, so the screen can re-ask whenever Lexi's state changes. */
    fun speechModelReady(): Boolean = speechModels.isInstalled()

    val state: StateFlow<LexiState> = assistant.state
    val wakeScore: StateFlow<Float> = assistant.wakeScore
    val turns: StateFlow<List<LexiTurn>> = assistant.turns
    val settings: StateFlow<LexiSettings> = settingsStore.settings

    /** Call only once the microphone permission is granted. */
    fun turnOn() {
        settingsStore.setEnabled(true)
        LexiService.start(context)
    }

    fun turnOff() {
        settingsStore.setEnabled(false)
        LexiService.stop(context)
        assistant.stop()
    }

    fun askNow() = assistant.askNow()

    fun askText(text: String) = assistant.askText(text)

    fun cancelTurn() = assistant.cancelTurn()

    /** Returns false when the phone cannot store the token encrypted. */
    fun saveToken(token: String): Boolean {
        if (!settingsStore.setAgentToken(token)) return false
        // Lexi stops with "add a token" when switched on without one; pick up where the
        // user left off instead of making them toggle it again.
        if (settings.value.enabled && state.value is LexiState.Failed) LexiService.start(context)
        return true
    }

    fun removeToken() {
        settingsStore.setAgentToken("")
    }

    fun saveBrain(tunnelUrl: String, lanUrl: String, orchestrator: String) =
        settingsStore.setBrain(tunnelUrl, lanUrl, orchestrator)

    fun setWakeThreshold(threshold: Float) = settingsStore.setWakeThreshold(threshold)
}
