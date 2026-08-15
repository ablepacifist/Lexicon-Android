<#
.SYNOPSIS
    Packages the Lexicon web build and the Mumble voice client into www/ and
    syncs them into the Capacitor Android project.

.DESCRIPTION
    LexiconAndroid holds no application source. This script pulls the build
    output of the two real repos into www/:

        www/            <- Lexicon/build/*                     (React app)
        www/voice/      <- mumble-bridge/public/*              (voice + text chat)

    The voice client's index.html addresses its assets from the site root
    (/css, /js, /react), which no longer resolves once it lives under /voice/,
    so those references are rewritten during the copy. Its /uploads references
    are repointed at the live bridge, which is what actually serves them.

.EXAMPLE
    ./scripts/build.ps1
    ./scripts/build.ps1 -SkipWebBuild      # reuse the existing Lexicon/build
    ./scripts/build.ps1 -Apk               # ...and then assemble a debug APK
#>
param(
    [string]$LexiconPath      = "..\Lexicon",
    [string]$BridgePublicPath = "..\discord-clone\myMumble\mumble-bridge\public",
    [string]$BridgeOrigin     = "https://voice.alex-dyakin.com",
    [switch]$SkipWebBuild,
    [switch]$Apk
)

$ErrorActionPreference = "Stop"

$repo  = Split-Path -Parent $PSScriptRoot
$www   = Join-Path $repo "www"
$voice = Join-Path $www  "voice"

function Resolve-RequiredPath([string]$path, [string]$label) {
    $full = Join-Path $repo $path
    if (-not (Test-Path $full)) {
        throw "$label not found at '$full'. Pass an explicit path, e.g. -$label <path>."
    }
    return (Resolve-Path $full).Path
}

$lexicon      = Resolve-RequiredPath $LexiconPath      "LexiconPath"
$bridgePublic = Resolve-RequiredPath $BridgePublicPath "BridgePublicPath"

# ── 1. Build Lexicon ──────────────────────────────────────────────
if ($SkipWebBuild) {
    Write-Host "[1/5] Skipping Lexicon build (-SkipWebBuild)" -ForegroundColor DarkGray
} else {
    Write-Host "[1/5] Building Lexicon ($lexicon)..." -ForegroundColor Cyan
    Push-Location $lexicon
    try {
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Lexicon build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$lexiconBuild = Join-Path $lexicon "build"
if (-not (Test-Path (Join-Path $lexiconBuild "index.html"))) {
    throw "No Lexicon build found at '$lexiconBuild'. Run without -SkipWebBuild."
}

# ── 2. Reset www/ ─────────────────────────────────────────────────
Write-Host "[2/5] Resetting www/..." -ForegroundColor Cyan
if (Test-Path $www) { Remove-Item -Recurse -Force $www }
New-Item -ItemType Directory -Force -Path $www   | Out-Null
New-Item -ItemType Directory -Force -Path $voice | Out-Null

# ── 3. Copy Lexicon build → www/ ──────────────────────────────────
Write-Host "[3/5] Copying Lexicon build -> www/" -ForegroundColor Cyan
Copy-Item -Path (Join-Path $lexiconBuild "*") -Destination $www -Recurse -Force

# ── 4. Copy voice client → www/voice/ ─────────────────────────────
# uploads/ is 12 MB of server-hosted user content and react-src/ is unbuilt
# source; neither belongs in the APK.
Write-Host "[4/5] Copying voice client -> www/voice/" -ForegroundColor Cyan
$skip = @("uploads", "react-src")
Get-ChildItem -Path $bridgePublic |
    Where-Object { $skip -notcontains $_.Name } |
    ForEach-Object { Copy-Item -Path $_.FullName -Destination $voice -Recurse -Force }

$voiceIndex = Join-Path $voice "index.html"
if (-not (Test-Path $voiceIndex)) { throw "Voice client index.html missing after copy." }

$html = Get-Content -Path $voiceIndex -Raw
# Site-root asset paths -> relative, now that this page lives under /voice/
$html = $html -replace '="/css/',    '="css/'
$html = $html -replace '="/js/',     '="js/'
$html = $html -replace '="/react/',  '="react/'
# Avatars and uploads are served by the bridge, not bundled
$html = $html -replace '="/uploads/', ('="' + $BridgeOrigin + '/uploads/')
Set-Content -Path $voiceIndex -Value $html -Encoding utf8

$remaining = Select-String -Path $voiceIndex -Pattern '(src|href)="/' -AllMatches
if ($remaining) {
    Write-Warning "Unrewritten site-root reference(s) left in www/voice/index.html:"
    $remaining.Matches | ForEach-Object { Write-Warning "  $($_.Value)" }
}

# ── 5. Sync into the Android project ──────────────────────────────
Write-Host "[5/5] Syncing Capacitor..." -ForegroundColor Cyan
Push-Location $repo
try {
    if (Test-Path (Join-Path $repo "android")) {
        npx cap sync android
    } else {
        Write-Host "      No android/ yet - running 'cap add android'" -ForegroundColor DarkGray
        npx cap add android
    }
    if ($LASTEXITCODE -ne 0) { throw "Capacitor sync failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

if ($Apk) {
    Write-Host "Assembling debug APK..." -ForegroundColor Cyan
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and
        -not (Test-Path (Join-Path $repo "android\local.properties"))) {
        throw "Android SDK not found. Install Android Studio (or the SDK command-line tools) and set ANDROID_HOME."
    }
    Push-Location (Join-Path $repo "android")
    try {
        ./gradlew assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembleDebug failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    $apkPath = Join-Path $repo "android\app\build\outputs\apk\debug\app-debug.apk"
    Write-Host "APK: $apkPath" -ForegroundColor Green
}

Write-Host "Done." -ForegroundColor Green
