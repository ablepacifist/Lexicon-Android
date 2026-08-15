# LexiconAndroid

Capacitor shell that packages **Lexicon** and the **Mumble voice/text chat client**
into an installable Android APK, talking to the same backend servers the website
already uses.

This repo contains **no application source code**. It wraps the *build output* of
two other repos, which remain the source of truth:

| Bundled as   | Comes from                                       | Repo             |
|--------------|--------------------------------------------------|------------------|
| `www/`       | `Lexicon/build/*`                                | `Lexicon` (dev)  |
| `www/voice/` | `discord-clone/myMumble/mumble-bridge/public/*`  | `discord-clone`  |

All real code changes belong in those repos, not here.

## Layout

```
LexiconAndroid/
├── android/              Capacitor-generated Gradle project — produces the APK
├── capacitor.config.ts   appId, appName, webDir: "www"
├── package.json          @capacitor/core + cli + android
├── scripts/build.ps1     builds Lexicon, assembles www/, runs cap sync
└── www/                  generated, gitignored — never edit by hand
```

## Prerequisites

- Node 18+
- **JDK 17** and the **Android SDK** (Android Studio, or the SDK command-line
  tools) with `ANDROID_HOME` set. Required for `cap sync` and Gradle.
- Sibling checkouts of `Lexicon` and `discord-clone`, i.e. this repo sits inside
  `full-back-end-server/` next to them. Override with `-LexiconPath` /
  `-BridgePublicPath` if your layout differs.

## Build

```powershell
./scripts/build.ps1              # build Lexicon, assemble www/, cap sync
./scripts/build.ps1 -SkipWebBuild  # reuse the existing Lexicon/build
./scripts/build.ps1 -Apk         # ...and assemble a debug APK
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.
Install with `adb install -r <path>`.

## How the native build differs from the website

The bundled pages are served from the WebView's own origin (`https://localhost`),
not from the real domains, so anything derived from `window.location` has to be
overridden. Each of these is a native-only branch — on the live site they are
dead code, and the website's behaviour is unchanged.

- **API URLs** — `Lexicon/src/utils/apiUrls.js` normally picks backends by
  sniffing the hostname, which under Capacitor would resolve to LAN IPs. Native
  always uses the public `*.alex-dyakin.com` URLs.
- **Auth** — the session cookie is `SameSite=Lax` and is never sent from the
  WebView's origin. Login sends `platform: "mobile"`, the backend returns a
  `mobileToken`, and `Lexicon/src/utils/apiFetch.js` presents it as
  `Authorization: Bearer <token>` on every Lexicon API request. The token is
  stored in `localStorage`, so login survives app restarts. Rotated tokens come
  back on the `X-Mobile-Token` response header.
- **Voice WebSocket** — `chat.js` derives its socket URL from `window.location`;
  native hardcodes `wss://voice.alex-dyakin.com`.
- **Voice asset paths** — the voice client addresses `/css`, `/js`, `/react` from
  the site root. `build.ps1` rewrites those to relative paths when copying into
  `www/voice/`, and repoints `/uploads` at the live bridge (which serves them).
- **Push notifications** — Web Push is skipped on native; it is unreliable in an
  embedded WebView and native push is deliberately out of scope for v1.

## Not included in v1

Offline/local caching, performance tuning, and native push (FCM).
