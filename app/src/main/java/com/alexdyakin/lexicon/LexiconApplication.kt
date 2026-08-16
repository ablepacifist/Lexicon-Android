package com.alexdyakin.lexicon

import android.app.Application
import com.alexdyakin.lexicon.media.AudiobookPlaybackPositionTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LexiconApplication : Application() {
	// Field injection eagerly starts app-lifetime audiobook resume tracking.
	@Inject lateinit var audiobookPlaybackPositionTracker: AudiobookPlaybackPositionTracker
}
