package com.alexdyakin.lexicon

import android.app.Application
import com.alexdyakin.lexicon.media.AudiobookPlaybackPositionTracker
import com.alexdyakin.lexicon.push.LexiconNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LexiconApplication : Application() {
	// Field injection eagerly starts app-lifetime audiobook resume tracking.
	@Inject lateinit var audiobookPlaybackPositionTracker: AudiobookPlaybackPositionTracker

	@Inject lateinit var notifier: LexiconNotifier

	override fun onCreate() {
		super.onCreate()
		// The channel must exist before any notification is posted, including one
		// that arrives while the app is being cold-started by a push.
		notifier.ensureChannel()
	}
}
