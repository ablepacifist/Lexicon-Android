import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The google-services plugin hard-fails when google-services.json is absent, which
// would break every build and CI run for anyone who has not set up Firebase yet.
// Applying it only when the file is present keeps the app buildable; push simply
// stays inactive until the file is dropped into app/.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

// ── Destination registry ──────────────────────────────────────────────────────────────
//
// The app ships to customers as a compiled APK - they never type a server address, so
// every network destination has to be baked in at build time, from the same registry
// every other module in the monorepo reads. See LexiconAndroid/.env.example and the root
// `.env.example` for the layering rules this mirrors.
//
// Layering (lowest -> highest priority):
//   1. destinations.defaults.json, committed, so this module builds standalone
//   2. the root `.env` (MASTER_ENV_FILE, else found by walking up from this module)
//   3. real process environment variables
// Only these whitelisted keys are ever pulled from the master .env - nothing else in
// that file (in particular no secret) can reach the app this way.
val destinationKeys = listOf(
    "LAN_HOST", "ALISON_LAN_IP", "LEXICON_PORT", "ALCHEMY_PORT", "POKEMON_PORT",
    "BRAIN_LAN_PORT", "PUBLIC_LEXICON_URL", "PUBLIC_ALCHEMY_URL", "PUBLIC_POKEMON_URL",
    "PUBLIC_BRIDGE_URL", "PUBLIC_BRAIN_URL",
)

/** Minimal reader for the flat, single-level string map destinations.defaults.json ships as. */
fun parseFlatJsonObject(text: String): Map<String, String> {
    fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
    val entry = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    return entry.findAll(text).associate { unescape(it.groupValues[1]) to unescape(it.groupValues[2]) }
}

/** Reads only [keys] out of a `KEY=value` file - the root `.env` format (no quotes, no inline comments). */
fun parseEnvFile(file: File, keys: Set<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    if (!file.exists()) return result
    file.forEachLine { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (line.isEmpty() || line.startsWith("#") || eq <= 0) return@forEachLine
        val key = line.substring(0, eq).trim()
        if (key in keys) result[key] = line.substring(eq + 1).trim()
    }
    return result
}

/**
 * `$MASTER_ENV_FILE` if set (silently absent if that path does not exist), else the
 * nearest ancestor directory of [startDir] holding a `.env` that looks like the master
 * registry (contains a `LAN_HOST=` line). Returns null - never throws - when neither is
 * found, so this module builds fine with no root checkout around it.
 */
fun findMasterEnvFile(startDir: File): File? {
    val explicitPath = providers.environmentVariable("MASTER_ENV_FILE").orNull
    if (!explicitPath.isNullOrBlank()) {
        val explicit = File(explicitPath)
        return if (explicit.exists()) explicit else null
    }
    var dir: File? = startDir
    while (dir != null) {
        val candidate = File(dir, ".env")
        if (candidate.exists() && candidate.readLines().any { it.trim().startsWith("LAN_HOST=") }) {
            return candidate
        }
        dir = dir.parentFile
    }
    return null
}

fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val destinationDefaultsFile = rootProject.layout.projectDirectory.file("destinations.defaults.json").asFile
val destinationDefaults = parseFlatJsonObject(destinationDefaultsFile.readText())
val masterEnvFile = findMasterEnvFile(rootProject.projectDir)
val masterEnvOverlay = masterEnvFile?.let { parseEnvFile(it, destinationKeys.toSet()) } ?: emptyMap()

// Real env var > master `.env` > committed defaults, key by key.
val resolvedDestinations: Map<String, String> = destinationKeys.associateWith { key ->
    providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }
        ?: masterEnvOverlay[key]
        ?: destinationDefaults[key]
        ?: ""
}

android {
    namespace = "com.alexdyakin.lexicon"
    compileSdk = 36

    // The publish workflow supplies these so the APK identifies itself with the
    // same version the server advertises. Without that, the in-app updater sees
    // a "new" version, installs it, still reads the old one, and loops forever.
    // Local builds fall back to the defaults.
    val appVersionCode = providers.environmentVariable("APP_VERSION_CODE").orNull
        ?.trim()?.toIntOrNull() ?: 1
    val appVersionName = providers.environmentVariable("APP_VERSION_NAME").orNull
        ?.trim()?.takeIf { it.isNotEmpty() } ?: "0.1.0"

    defaultConfig {
        applicationId = "com.alexdyakin.lexicon"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The destination registry, resolved above - see ApiUrls.kt and LexiSettings.kt.
        resolvedDestinations.forEach { (key, value) ->
            buildConfigField("String", key, "\"${escapeForBuildConfig(value)}\"")
        }
    }

    val releaseKeystoreFile = providers.environmentVariable("APP_RELEASE_KEYSTORE_FILE").orNull
    val releaseKeystorePassword = providers.environmentVariable("APP_RELEASE_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("APP_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("APP_RELEASE_KEY_PASSWORD").orNull
    val releaseKeystoreType = providers.environmentVariable("APP_RELEASE_KEYSTORE_TYPE").orNull ?: "JKS"

    signingConfigs {
        create("release") {
            if (
                !releaseKeystoreFile.isNullOrBlank() &&
                !releaseKeystorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
            ) {
                storeFile = File(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                this.storeType = releaseKeystoreType
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (
                !releaseKeystoreFile.isNullOrBlank() &&
                !releaseKeystorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log is a stub in unit tests and throws "not mocked" by
            // default. BridgeConnection logs on the reconnect path, which is exactly
            // what the JVM tests exercise.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Lexi's native runtimes (ONNX Runtime, Vosk) are ~170 MB across four ABIs when
        // stored uncompressed, which is the modern default. Compressing them roughly halves
        // the APK the in-app updater has to download, at the cost of the installer
        // extracting them to disk once.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Push notifications. Safe to compile without google-services.json - the code
    // guards on FirebaseApp being initialised before touching any of it.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Media3: background playback, lock-screen/Bluetooth controls, seeking
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.urlconnection)   // JavaNetCookieJar
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // Lexi: the openWakeWord models run on ONNX Runtime, speech recognition on Vosk.
    // Both are fully on-device; only the transcript ever leaves the phone.
    implementation(libs.onnxruntime.android)
    implementation(libs.vosk.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Runs the real wake-word models in JVM tests; see the classpath exclusion below.
    testImplementation(libs.onnxruntime.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// The Android and desktop ONNX Runtime artifacts ship the same `ai.onnxruntime` classes.
// Unit tests run on the desktop JVM, where only the desktop build has native libraries,
// so the Android one is removed from their runtime classpath rather than left to win a
// classpath-order coin toss.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

// ── Lexi on-device models ─────────────────────────────────────────────────────────────
//
// The wake-word models (3.7 MB) ship inside the APK. They are downloaded at build time
// rather than committed, and pinned by SHA-256, so a build can never quietly pick up a
// different model from the one the wake-word golden test was checked against. They are
// the exact openWakeWord 0.5.1 release assets Lexi's Python build uses on the Pi. The
// download cache sits outside build/ so `clean` keeps it.
//
// The speech-recognition model is deliberately NOT bundled: at 41 MB it would ride along
// in every update the in-app updater downloads. The app fetches it once, the first time
// Lexi is switched on; see SpeechModelInstaller.

val lexiModelCache: Directory = rootProject.layout.projectDirectory.dir(".lexi-models")

val lexiWakeModels = mapOf(
    "melspectrogram.onnx" to "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
    "embedding_model.onnx" to "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
    "hey_jarvis_v0.1.onnx" to "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
)
val LEXI_WAKE_RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"

/** Fills the cache with every pinned model, skipping files already present and intact. */
abstract class DownloadLexiModels : DefaultTask() {
    /** File name to "sha256 url". */
    @get:Input
    abstract val files: MapProperty<String, String>

    @get:OutputDirectory
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = cacheDir.get().asFile.apply { mkdirs() }
        files.get().forEach { (name, spec) ->
            val (expected, url) = spec.split(" ", limit = 2)
            val target = dir.resolve(name)
            if (target.exists() && sha256(target) == expected) return@forEach

            logger.lifecycle("Lexi: downloading $name")
            val partial = dir.resolve("$name.part")
            URI(url).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256(partial)
            if (actual != expected) {
                partial.delete()
                throw GradleException(
                    "Lexi model $name failed its checksum (expected $expected, got $actual). " +
                        "Refusing to build with a model nobody has verified.",
                )
            }
            target.delete()
            check(partial.renameTo(target)) { "Could not move $partial into place" }
        }
    }

    // Build scripts cannot call their own top-level functions from a task class.
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Copies the cached wake-word models into the APK's assets under `lexi/wakeword/`. */
abstract class StageLexiModels : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cacheDir: DirectoryProperty

    @get:Input
    abstract val wakeModels: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val cache = cacheDir.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val wake = out.resolve("lexi/wakeword").apply { mkdirs() }
        wakeModels.get().forEach { cache.resolve(it).copyTo(wake.resolve(it)) }
    }
}

val downloadLexiModels = tasks.register<DownloadLexiModels>("downloadLexiModels") {
    files.set(lexiWakeModels.mapValues { (name, sha) -> "$sha $LEXI_WAKE_RELEASE$name" })
    cacheDir.set(lexiModelCache)
}

androidComponents {
    onVariants { variant ->
        val stage = tasks.register<StageLexiModels>(
            "stage${variant.name.replaceFirstChar { it.uppercase() }}LexiModels",
        ) {
            cacheDir.set(downloadLexiModels.flatMap { it.cacheDir })
            wakeModels.set(lexiWakeModels.keys.toList())
        }
        variant.sources.assets?.addGeneratedSourceDirectory(stage, StageLexiModels::outputDir)
    }
}

// The wake-word golden test runs the real models, straight from the download cache.
tasks.withType<Test>().configureEach {
    dependsOn(downloadLexiModels)
    systemProperty("lexi.models", lexiModelCache.asFile.absolutePath)
}
