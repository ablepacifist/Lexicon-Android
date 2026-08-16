import java.io.File

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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
