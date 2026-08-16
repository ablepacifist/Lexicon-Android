# Lexicon — Android

Native Android app (Kotlin + Jetpack Compose + AndroidX) for the Lexicon stack.
It talks directly to the existing REST backends — there is no WebView and no
bundled web build. The website is a **reference for behaviour only**; every
screen here is native.

## Why it isn't a WebView any more

The first version wrapped the website in Capacitor. That shell is gone. It could
not do the things that actually matter on a phone:

- a backgrounded WebView is suspended, so voice dropped on screen-lock and
  audio stopped when you switched apps
- runtime permissions (location for the Pokémon map) were awkward at best
- `window.confirm`/`alert` dialogs and cookie-based sessions behaved differently
  inside the WebView than in a browser

## Stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Navigation | `androidx.navigation:navigation-compose` |
| HTTP | OkHttp + Retrofit |
| JSON | kotlinx.serialization |
| Images | Coil |
| Credentials | `androidx.security:security-crypto` (`EncryptedSharedPreferences`) |
| Min / target SDK | 24 / 36 |

## Auth

One login covers everything, using the bearer token LexiconServer issues:

1. `POST api/auth/login` with `platform: "mobile"` returns a `mobileToken`.
2. The token is stored **encrypted** via `EncryptedSharedPreferences` — not in
   plain storage the way the old WebView shell kept it.
3. `AuthInterceptor` attaches `Authorization: Bearer <token>` to the hosts that
   accept it, and stores any rotated token returned on `X-Mobile-Token`.

The token goes to Lexicon (which issues and rotates it) and Pokémon (which
validates it read-only against the shared database). **Alchemy is deliberately
excluded** — its endpoints take an explicit `playerId` and never read a session,
so the token means nothing there.

## Build

```powershell
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew installDebug           # build + install on a connected device
```

Requires the Android SDK (platform 36, build-tools 36) with `ANDROID_HOME` set or
`local.properties` pointing at it.

### JDK: use 21, not 25

Build on **JDK 21**. Android Gradle Plugin 8.13 rejects JDK 25 and fails with a
bare, unexplained `25.0.2`:

```
* What went wrong:
25.0.2
```

This matters because **Android Studio bundles JBR 25 and uses it by default**, so
the IDE fails while the command line succeeds. `.idea/gradle.xml` pins the Gradle
JVM to `C:/Users/HP/Documents/jdk-21`; if Studio still picks its own, set it at
*Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*.

JDK 17 does not work either — Compose and AGP need 21.

### local.properties

Write the SDK path with **forward slashes**. In a Java `.properties` file a
backslash is an escape character, so `sdk.dir=C:\Users\HP\...` silently parses as
`C:UsersHP...` and the build dies with "The filename, directory name, or volume
label syntax is incorrect".

In Android Studio, open the **repository root** — it is a normal Gradle project
now, not a Capacitor subfolder.

## Assets

Art is pulled from the existing repos and lives in `app/src/main/res/drawable/`:

| Drawable | Source |
|---|---|
| `forage`, `brew`, `consume`, `drink` | `Lexicon/src/assets/images/` |
| `bg_login`, `bg_dashboard`, `bg_lexicon_room`, `banner` | `Lexicon/src/assets/images/` |
| `logo_lexicon`, `logo_runed` | `Lexicon/src/assets/images/` |

Still untapped, and the reason the Pokémon screens should be fun to build:
`pokemon/pogo_assets/` (~18.5k images, 3D assets and sounds) and
`pokemon/moreAssets/pokesprite/` (~11k sprites).

## Status

**Done**
- Project scaffold, theme (palette drawn from the Lexicon room art), navigation
- Encrypted token storage, auth interceptor with rotation handling
- Login screen — Ken Burns background, animated logo entry, animated errors
- Alchemy — inventory list, forage with a three-phase animation, consume

**Next**
- Pokémon: map + real location (the manifest permissions are already declared)
- Lexicon media: Media3 for real background playback and lock-screen controls
- Voice: native Mumble audio — the largest piece by far
