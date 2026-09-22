package com.alexdyakin.lexicon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alexdyakin.lexicon.push.PushTokenRegistrar
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alexdyakin.lexicon.data.TokenStore
import com.alexdyakin.lexicon.lexi.LexiService
import com.alexdyakin.lexicon.lexi.LexiSettingsStore
import com.alexdyakin.lexicon.ui.lexi.LexiScreen
import com.alexdyakin.lexicon.ui.components.LaunchSequence
import com.alexdyakin.lexicon.ui.alchemy.AlchemyScreen
import com.alexdyakin.lexicon.ui.alchemy.KnowledgeBookScreen
import com.alexdyakin.lexicon.ui.alchemy.HoldfastScreen
import com.alexdyakin.lexicon.ui.events.EventsScreen
import com.alexdyakin.lexicon.ui.home.HomeScreen
import com.alexdyakin.lexicon.ui.lexicon.LexiconScreen
import com.alexdyakin.lexicon.ui.lexicon.MediaLibraryScreen
import com.alexdyakin.lexicon.ui.lexicon.DedicatedMediaMode
import com.alexdyakin.lexicon.ui.lexicon.DedicatedMediaPlayerScreen
import com.alexdyakin.lexicon.ui.lexicon.PlayerScreen
import com.alexdyakin.lexicon.ui.lexicon.PlaylistScreen
import com.alexdyakin.lexicon.ui.lexicon.MediaUploadScreen
import com.alexdyakin.lexicon.ui.lexicon.LiveStreamScreen
import com.alexdyakin.lexicon.ui.lexicon.QueueManagerScreen
import com.alexdyakin.lexicon.ui.lexicon.StorageDashboardScreen
import com.alexdyakin.lexicon.ui.login.LoginScreen
import com.alexdyakin.lexicon.ui.login.RegisterScreen
import com.alexdyakin.lexicon.ui.pokemon.PokemonScreen
import com.alexdyakin.lexicon.ui.pokemon.PokedexScreen
import com.alexdyakin.lexicon.ui.pokemon.PokemonShopScreen
import com.alexdyakin.lexicon.ui.pokemon.EggsScreen
import com.alexdyakin.lexicon.ui.pokemon.PokemonDetailScreen
import com.alexdyakin.lexicon.ui.profile.ProfileScreen
import com.alexdyakin.lexicon.ui.notifications.NotificationsScreen
import com.alexdyakin.lexicon.ui.theme.LexiconTheme
import com.alexdyakin.lexicon.ui.voice.VoiceScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Screen transition duration. Short enough not to slow navigation down. */
private const val NAV_ANIM_MS = 340

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val LEXICON = "lexicon"
    const val LIBRARY = "library"
    const val AUDIO_PLAYER = "audio-player"
    const val AUDIO_PLAYER_MEDIA = "audio-player/{mediaId}"
    const val AUDIO_PLAYER_PLAYLIST = "audio-player/playlist/{playlistId}"
    const val VIDEO_PLAYER = "video-player"
    const val VIDEO_PLAYER_MEDIA = "video-player/{mediaId}"
    const val VIDEO_PLAYER_PLAYLIST = "video-player/playlist/{playlistId}"
    const val AUDIOBOOKS = "audiobooks"
    const val AUDIOBOOKS_MEDIA = "audiobooks/{mediaId}"
    const val AUDIOBOOKS_PLAYLIST = "audiobooks/playlist/{playlistId}"
    const val PLAYER = "player"
    const val MEDIA_UPLOAD = "media-upload"
    const val MUSIC_STREAM = "music-stream"
    const val VIDEO_STREAM = "video-stream"
    const val QUEUE_MANAGER = "queue-manager?channel={channel}"
    const val STORAGE = "storage"
    const val PLAYLISTS = "playlists"
    const val PROFILE = "profile"
    const val ALCHEMY = "alchemy"
    const val KNOWLEDGE = "knowledge"
    const val HOLDFAST = "holdfast"
    const val EVENTS = "events"
    const val NOTIFICATIONS = "notifications"
    const val POKEMON = "pokemon"
    const val POKEDEX = "pokedex"
    const val POKEMON_SHOP = "pokemon-shop"
    const val EGGS = "eggs"
    const val POKEMON_DETAIL = "pokemon-detail/{id}"
    const val VOICE = "voice"
    const val LEXI = "lexi"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar
    @Inject lateinit var lexiSettings: LexiSettingsStore

    // Android 13+ will not show notifications until the user grants this.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        askForNotificationPermission()
        // Registering on every launch also repairs a token the backend lost or
        // that rotated while the app was not running.
        lifecycleScope.launch { pushTokenRegistrar.syncCurrentToken() }

        resumeLexi()

        setContent {
            LexiconTheme {
                LexiconApp(
                    tokenStore = tokenStore,
                    initialRoute = intent?.getStringExtra(EXTRA_ROUTE),
                    onSignedOut = {
                        // Lexi speaks to the brain as the signed-in user, so it must not
                        // carry on listening for whoever picks the phone up next.
                        lexiSettings.setEnabled(false)
                        LexiService.stop(this)
                    },
                )
            }
        }
    }

    /**
     * Picks Lexi back up if the user left it on. A microphone foreground service can only be
     * started while the app is in the foreground, so this, rather than boot, is where Lexi
     * comes back after a restart.
     */
    private fun resumeLexi() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (lexiSettings.settings.value.enabled && tokenStore.isLoggedIn && micGranted) {
            LexiService.start(this)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** A route to open on launch, used by Lexi's notification. */
        const val EXTRA_ROUTE = "route"
    }
}

@Composable
private fun LexiconApp(tokenStore: TokenStore, initialRoute: String?, onSignedOut: () -> Unit) {
    val navController = rememberNavController()

    // Shown once per cold start, over the app rather than as a nav destination, so it
    // never lands on the back stack and cannot be returned to.
    var launching by rememberSaveable { mutableStateOf(true) }

    // A stored bearer token means the session survived the app being killed
    val start = remember { if (tokenStore.isLoggedIn) Routes.HOME else Routes.LOGIN }
    val displayName = remember { tokenStore.username ?: "traveller" }

    fun signOut() {
        onSignedOut()
        tokenStore.clear()
        navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
    }

    // Applied to every destination: forward navigation slides in from the right and
    // back pops the other way, so the stack has a sense of direction.
    Box(Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = start,
        // The incoming screen travels a third of the width and grows very slightly into
        // place while the outgoing one recedes, which reads as one screen sitting on top
        // of another rather than as a cross-fade you have to look for.
        enterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it / 3 } +
                fadeIn(animationSpec = tween(NAV_ANIM_MS)) +
                scaleIn(animationSpec = tween(NAV_ANIM_MS), initialScale = 0.94f)
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { -it / 5 } +
                fadeOut(animationSpec = tween(NAV_ANIM_MS)) +
                scaleOut(animationSpec = tween(NAV_ANIM_MS), targetScale = 0.96f)
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { -it / 5 } +
                fadeIn(animationSpec = tween(NAV_ANIM_MS)) +
                scaleIn(animationSpec = tween(NAV_ANIM_MS), initialScale = 0.96f)
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_MS, easing = FastOutSlowInEasing)) { it / 3 } +
                fadeOut(animationSpec = tween(NAV_ANIM_MS)) +
                scaleOut(animationSpec = tween(NAV_ANIM_MS), targetScale = 0.94f)
        },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onRegister = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) { RegisterScreen(onBackToLogin = { navController.popBackStack() }) }

        composable(Routes.HOME) {
            HomeScreen(
                displayName = displayName,
                onOpen = { key -> navController.navigate(key) },
                onLogout = ::signOut,
            )
        }

        composable(Routes.LEXICON) {
            // Card routes come straight from the dashboard definition, mirroring the web's
            // `<Link to>` values — see LexiconScreen's SECTIONS.
            LexiconScreen(
                onBack = { navController.popBackStack() },
                onOpen = { route -> navController.navigate(route) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                displayName = displayName,
            )
        }
        composable(Routes.LIBRARY) {
            MediaLibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                onOpenUpload = { navController.navigate(Routes.MEDIA_UPLOAD) },
            )
        }
        composable(Routes.MEDIA_UPLOAD) { MediaUploadScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.AUDIO_PLAYER) {
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
            )
        }
        composable(Routes.AUDIO_PLAYER_MEDIA) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialMediaId = entry.arguments?.getString("mediaId")?.toIntOrNull(),
            )
        }
        composable(Routes.AUDIO_PLAYER_PLAYLIST) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialPlaylistId = entry.arguments?.getString("playlistId")?.toIntOrNull(),
            )
        }
        composable(Routes.VIDEO_PLAYER) {
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.VIDEO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
            )
        }
        composable(Routes.VIDEO_PLAYER_MEDIA) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.VIDEO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialMediaId = entry.arguments?.getString("mediaId")?.toIntOrNull(),
            )
        }
        composable(Routes.VIDEO_PLAYER_PLAYLIST) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.VIDEO,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialPlaylistId = entry.arguments?.getString("playlistId")?.toIntOrNull(),
            )
        }
        composable(Routes.AUDIOBOOKS) {
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIOBOOK,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
            )
        }
        composable(Routes.AUDIOBOOKS_MEDIA) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIOBOOK,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialMediaId = entry.arguments?.getString("mediaId")?.toIntOrNull(),
            )
        }
        composable(Routes.AUDIOBOOKS_PLAYLIST) { entry ->
            DedicatedMediaPlayerScreen(
                mode = DedicatedMediaMode.AUDIOBOOK,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                initialPlaylistId = entry.arguments?.getString("playlistId")?.toIntOrNull(),
            )
        }
        // Two fixed-channel stream routes, matching /music-stream and /video-stream on the web.
        // There is no combined "live stream" hub — the web's /live-stream is only a redirect.
        composable(Routes.MUSIC_STREAM) {
            LiveStreamScreen(
                channel = "music",
                onBack = { navController.popBackStack() },
                onOpenQueueManager = { channel -> navController.navigate("queue-manager?channel=$channel") },
            )
        }
        composable(Routes.VIDEO_STREAM) {
            LiveStreamScreen(
                channel = "video",
                onBack = { navController.popBackStack() },
                onOpenQueueManager = { channel -> navController.navigate("queue-manager?channel=$channel") },
            )
        }
        composable(
            Routes.QUEUE_MANAGER,
            arguments = listOf(navArgument("channel") { defaultValue = "video" }),
        ) { entry ->
            QueueManagerScreen(
                channel = entry.arguments?.getString("channel") ?: "video",
                onBack = { navController.popBackStack() },
                onOpenStream = { channel ->
                    navController.navigate(if (channel == "music") Routes.MUSIC_STREAM else Routes.VIDEO_STREAM)
                },
            )
        }
        composable(Routes.STORAGE) { StorageDashboardScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PLAYER) { PlayerScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PLAYLISTS) { PlaylistScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PROFILE) { ProfileScreen(onBack = { navController.popBackStack() }) }

        composable(Routes.ALCHEMY) { AlchemyScreen(onBack = { navController.popBackStack() }, onOpenKnowledge = { navController.navigate(Routes.KNOWLEDGE) }) }
        composable(Routes.KNOWLEDGE) { KnowledgeBookScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.HOLDFAST) { HoldfastScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.EVENTS) { EventsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.POKEMON) { PokemonScreen(onBack = { navController.popBackStack() }, onOpenPokedex = { navController.navigate(Routes.POKEDEX) }, onOpenShop = { navController.navigate(Routes.POKEMON_SHOP) }, onOpenEggs = { navController.navigate(Routes.EGGS) }, onOpenPokemon = { id -> navController.navigate("pokemon-detail/$id") }) }
        composable(Routes.POKEDEX) { PokedexScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.POKEMON_SHOP) { PokemonShopScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.EGGS) { EggsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.POKEMON_DETAIL) { entry -> PokemonDetailScreen(entry.arguments?.getString("id")?.toLongOrNull() ?: 0, onBack = { navController.popBackStack() }) }
        composable(Routes.VOICE) { VoiceScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.LEXI) { LexiScreen(onBack = { navController.popBackStack() }) }
    }

    // Deep link from a notification, honoured only for a signed-in user.
    LaunchedEffect(Unit) {
        if (initialRoute == Routes.LEXI && start == Routes.HOME) navController.navigate(Routes.LEXI)
    }

        if (launching) {
            LaunchSequence(onFinished = { launching = false })
        }
    }
}
