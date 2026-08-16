#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  publish-android-update.sh --apk <path-to-apk> --version-code <int> --version-name <name> [options]

Required:
  --apk <path>                Local APK file to publish
  --version-code <int>        APP_UPDATE_VERSION_CODE
  --version-name <string>     APP_UPDATE_VERSION_NAME

Options:
  --changelog <text>          APP_UPDATE_CHANGELOG (default: empty)
  --critical <true|false>     APP_UPDATE_CRITICAL (default: false)
  --sha256 <hex>              Override SHA-256 (default: auto-compute from APK)
  --download-url <url>        APP_UPDATE_DOWNLOAD_URL (default: https://api.alex-dyakin.com/api/app/download/latest)
  --apk-path <path>           APP_UPDATE_APK_PATH (default: <release-dir>/lexicon-latest.apk)
  --public-metadata <bool>    APP_UPDATE_PUBLIC_METADATA (default: false)
  --public-download <bool>    APP_UPDATE_PUBLIC_DOWNLOAD (default: false)
  --host <hostname>           SSH host (required unless --dry-run)
  --user <username>           SSH user (required unless --dry-run)
  --port <port>               SSH port (default: 22)
  --target-os <linux|windows> Remote host OS (default: windows)
  --release-dir <path>        Remote APK directory (windows default: full-back-end-server/releases)
  --env-path <path>           Remote .env path (windows default: full-back-end-server/.env)
  --restart-cmd <command>     Remote restart command after publish
  --dry-run                   Print actions without uploading/changing remote files

Environment alternatives:
  DEPLOY_HOST, DEPLOY_USER, DEPLOY_PORT, DEPLOY_TARGET_OS, DEPLOY_RELEASE_DIR, DEPLOY_ENV_PATH, DEPLOY_RESTART_CMD
EOF
}

APK_PATH=""
VERSION_CODE=""
VERSION_NAME=""
CHANGELOG=""
CRITICAL="false"
SHA256_OVERRIDE=""
DOWNLOAD_URL="https://api.alex-dyakin.com/api/app/download/latest"
PUBLIC_METADATA="false"
PUBLIC_DOWNLOAD="false"

HOST="${DEPLOY_HOST:-}"
USER="${DEPLOY_USER:-}"
PORT="${DEPLOY_PORT:-22}"
TARGET_OS="${DEPLOY_TARGET_OS:-windows}"
REMOTE_RELEASE_DIR="${DEPLOY_RELEASE_DIR:-full-back-end-server/releases}"
REMOTE_ENV_PATH="${DEPLOY_ENV_PATH:-full-back-end-server/.env}"
RESTART_CMD="${DEPLOY_RESTART_CMD:-}"
APK_ENV_PATH=""
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK_PATH="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2"; shift 2 ;;
    --critical) CRITICAL="$2"; shift 2 ;;
    --sha256) SHA256_OVERRIDE="$2"; shift 2 ;;
    --download-url) DOWNLOAD_URL="$2"; shift 2 ;;
    --apk-path) APK_ENV_PATH="$2"; shift 2 ;;
    --public-metadata) PUBLIC_METADATA="$2"; shift 2 ;;
    --public-download) PUBLIC_DOWNLOAD="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --user) USER="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --target-os) TARGET_OS="$2"; shift 2 ;;
    --release-dir) REMOTE_RELEASE_DIR="$2"; shift 2 ;;
    --env-path) REMOTE_ENV_PATH="$2"; shift 2 ;;
    --restart-cmd) RESTART_CMD="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$APK_PATH" || -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  echo "Missing required arguments."
  usage
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH"
  exit 1
fi

if [[ "$TARGET_OS" != "linux" && "$TARGET_OS" != "windows" ]]; then
  echo "--target-os must be linux or windows"
  exit 1
fi

if [[ -z "$APK_ENV_PATH" ]]; then
  APK_ENV_PATH="$REMOTE_RELEASE_DIR/lexicon-latest.apk"
fi

if [[ -n "$SHA256_OVERRIDE" ]]; then
  SHA256="$SHA256_OVERRIDE"
else
  if command -v sha256sum >/dev/null 2>&1; then
    SHA256="$(sha256sum "$APK_PATH" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
  else
    echo "No SHA-256 tool found (sha256sum/shasum)."
    exit 1
  fi
fi

REMOTE_VERSIONED_APK="$REMOTE_RELEASE_DIR/lexicon-${VERSION_NAME}-${VERSION_CODE}.apk"
TMP_REMOTE_APK="$REMOTE_VERSIONED_APK.tmp"
CHANGELOG_B64="$(printf '%s' "$CHANGELOG" | base64 | tr -d '\n')"

if [[ "$DRY_RUN" == "true" ]]; then
  cat <<EOF
[DRY RUN] Would publish APK:
  target os:           $TARGET_OS
  local apk:           $APK_PATH
  remote versioned apk:$REMOTE_VERSIONED_APK
  remote latest apk:   $APK_ENV_PATH
  remote env file:     $REMOTE_ENV_PATH
  download url:        $DOWNLOAD_URL
  sha256:              $SHA256
  critical:            $CRITICAL
  public metadata:     $PUBLIC_METADATA
  public download:     $PUBLIC_DOWNLOAD
EOF
  exit 0
fi

if [[ -z "$HOST" || -z "$USER" ]]; then
  echo "--host and --user are required unless --dry-run is used."
  exit 1
fi

if [[ "$TARGET_OS" == "linux" ]]; then
  echo "Uploading APK to $USER@$HOST:$TMP_REMOTE_APK"
  ssh -p "$PORT" "$USER@$HOST" "mkdir -p '$REMOTE_RELEASE_DIR'"
  scp -P "$PORT" "$APK_PATH" "$USER@$HOST:$TMP_REMOTE_APK"
  ssh -p "$PORT" "$USER@$HOST" "mv '$TMP_REMOTE_APK' '$REMOTE_VERSIONED_APK'; ln -sfn '$REMOTE_VERSIONED_APK' '$APK_ENV_PATH'"

  ssh -p "$PORT" "$USER@$HOST" \
    "REMOTE_ENV_PATH='$REMOTE_ENV_PATH' \
     APP_UPDATE_VERSION_CODE='$VERSION_CODE' \
     APP_UPDATE_VERSION_NAME='$VERSION_NAME' \
     APP_UPDATE_DOWNLOAD_URL='$DOWNLOAD_URL' \
     APP_UPDATE_APK_PATH='$APK_ENV_PATH' \
     APP_UPDATE_CRITICAL='$CRITICAL' \
     APP_UPDATE_CHANGELOG_B64='$CHANGELOG_B64' \
     APP_UPDATE_SHA256='$SHA256' \
     APP_UPDATE_PUBLIC_METADATA='$PUBLIC_METADATA' \
     APP_UPDATE_PUBLIC_DOWNLOAD='$PUBLIC_DOWNLOAD' \
     bash -s" <<'REMOTE_EOF'
set -euo pipefail

touch "$REMOTE_ENV_PATH"

set_env_value() {
  local key="$1"
  local value="$2"
  local file="$3"
  local tmp
  tmp="$(mktemp)"
  local found="false"
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$key="* ]]; then
      printf '%s=%s\n' "$key" "$value" >> "$tmp"
      found="true"
    else
      printf '%s\n' "$line" >> "$tmp"
    fi
  done < "$file"

  if [[ "$found" == "false" ]]; then
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$file"
}

CHANGELOG="$(printf '%s' "$APP_UPDATE_CHANGELOG_B64" | base64 -d)"

set_env_value "APP_UPDATE_VERSION_CODE" "$APP_UPDATE_VERSION_CODE" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_VERSION_NAME" "$APP_UPDATE_VERSION_NAME" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_DOWNLOAD_URL" "$APP_UPDATE_DOWNLOAD_URL" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_APK_PATH" "$APP_UPDATE_APK_PATH" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_CRITICAL" "$APP_UPDATE_CRITICAL" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_CHANGELOG" "$CHANGELOG" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_SHA256" "$APP_UPDATE_SHA256" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_PUBLIC_METADATA" "$APP_UPDATE_PUBLIC_METADATA" "$REMOTE_ENV_PATH"
set_env_value "APP_UPDATE_PUBLIC_DOWNLOAD" "$APP_UPDATE_PUBLIC_DOWNLOAD" "$REMOTE_ENV_PATH"
REMOTE_EOF
else
  tmp_name="lexicon-${VERSION_NAME}-${VERSION_CODE}.upload.tmp.apk"
  echo "Uploading APK to $USER@$HOST:~/$tmp_name"
  scp -P "$PORT" "$APK_PATH" "$USER@$HOST:~/$tmp_name"

  # Quote a value for safe embedding in a PowerShell single-quoted string.
  ps_quote() { printf "'%s'" "$(printf '%s' "${1:-}" | sed "s/'/''/g")"; }

  # Base64(UTF-16LE) for `powershell -EncodedCommand`. Encoding the whole script
  # avoids every quoting pitfall of the bash -> ssh -> cmd.exe -> powershell chain;
  # the command line that reaches the remote host is plain base64.
  utf16le_b64() {
    if command -v iconv > /dev/null 2>&1; then
      iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n'
    elif command -v python3 > /dev/null 2>&1; then
      python3 -c 'import sys,base64; sys.stdout.write(base64.b64encode(sys.stdin.buffer.read().decode("utf-8").encode("utf-16-le")).decode())'
    else
      echo "Need iconv or python3 to encode the remote PowerShell command." >&2
      exit 1
    fi
  }

  # Inputs are assigned to PowerShell variables up front. Names must not collide
  # case-insensitively with the resolved variables below - PowerShell treats
  # $LatestApk and $latestApk as the same variable.
  ps_values="$(printf '%s\n' \
    "\$ReleaseDirIn   = $(ps_quote "$REMOTE_RELEASE_DIR")" \
    "\$EnvPathIn      = $(ps_quote "$REMOTE_ENV_PATH")" \
    "\$VersionedApkIn = $(ps_quote "$REMOTE_VERSIONED_APK")" \
    "\$LatestApkIn    = $(ps_quote "$APK_ENV_PATH")" \
    "\$TmpNameIn      = $(ps_quote "$tmp_name")" \
    "\$VersionCode    = $(ps_quote "$VERSION_CODE")" \
    "\$VersionName    = $(ps_quote "$VERSION_NAME")" \
    "\$DownloadUrl    = $(ps_quote "$DOWNLOAD_URL")" \
    "\$Critical       = $(ps_quote "$CRITICAL")" \
    "\$ChangelogB64   = $(ps_quote "$CHANGELOG_B64")" \
    "\$Sha256         = $(ps_quote "$SHA256")" \
    "\$PublicMetadata = $(ps_quote "$PUBLIC_METADATA")" \
    "\$PublicDownload = $(ps_quote "$PUBLIC_DOWNLOAD")")"

  ps_logic="$(cat <<'PSLOGIC'
$ErrorActionPreference = 'Stop'

function Resolve-DeployPath([string]$p) {
  if ([System.IO.Path]::IsPathRooted($p)) { return $p }
  return (Join-Path $HOME $p)
}

function Set-EnvValue([string]$file, [string]$key, [string]$value) {
  # The read MUST specify UTF-8. PowerShell 5.1's Get-Content defaults to the ANSI
  # codepage, so a UTF-8 character read as ANSI becomes several mojibake characters,
  # which are then written back as UTF-8 and re-read as still more characters. That
  # feedback loop grew a single em-dash in a comment into a 1.6 GB line.
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  if (Test-Path $file) {
    # A .env is a few KB. Anything past 1 MB means something has corrupted it, and
    # rewriting would compound the damage - stop and let a human look instead.
    $sizeBytes = (Get-Item $file).Length
    if ($sizeBytes -gt 1MB) {
      throw "Refusing to edit '$file': it is $([math]::Round($sizeBytes/1MB,1)) MB, which is far too large for an env file. Inspect it before publishing again."
    }
    $lines = @([System.IO.File]::ReadAllLines($file, [System.Text.Encoding]::UTF8))
  } else {
    $lines = @()
  }
  $pattern = '^' + [regex]::Escape($key) + '='
  $found = $false
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $pattern) { $lines[$i] = "$key=$value"; $found = $true; break }
  }
  if (-not $found) { $lines += "$key=$value" }
  # LF endings, no BOM - keeps the file readable by dotenv-style parsers.
  [System.IO.File]::WriteAllText($file, (($lines -join "`n") + "`n"), $utf8NoBom)
}

$releaseDir   = Resolve-DeployPath $ReleaseDirIn
$envPath      = Resolve-DeployPath $EnvPathIn
$versionedApk = Resolve-DeployPath $VersionedApkIn
$latestApk    = Resolve-DeployPath $LatestApkIn
$tmpUploaded  = Join-Path $HOME $TmpNameIn

New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $versionedApk) -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $latestApk) -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $envPath) -Force | Out-Null

Move-Item -Path $tmpUploaded -Destination $versionedApk -Force
Copy-Item -Path $versionedApk -Destination $latestApk -Force

# Create the .env only when absent. New-Item -Force on an existing file would
# truncate it, discarding every unrelated setting the server needs.
if (-not (Test-Path $envPath)) { New-Item -ItemType File -Path $envPath | Out-Null }

$changelog = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($ChangelogB64))

# The deploy paths are relative to the SSH user's home, but the server resolves
# app.update.apk-path against its own working directory - a different base, which
# yielded a 404 on download. Write the resolved absolute path so neither side has
# to guess, with forward slashes so nothing downstream treats \ as an escape.
$latestApkForEnv = $latestApk -replace '\\', '/'

Set-EnvValue $envPath 'APP_UPDATE_VERSION_CODE'    $VersionCode
Set-EnvValue $envPath 'APP_UPDATE_VERSION_NAME'    $VersionName
Set-EnvValue $envPath 'APP_UPDATE_DOWNLOAD_URL'    $DownloadUrl
Set-EnvValue $envPath 'APP_UPDATE_APK_PATH'        $latestApkForEnv
Set-EnvValue $envPath 'APP_UPDATE_CRITICAL'        $Critical
Set-EnvValue $envPath 'APP_UPDATE_CHANGELOG'       $changelog
Set-EnvValue $envPath 'APP_UPDATE_SHA256'          $Sha256
Set-EnvValue $envPath 'APP_UPDATE_PUBLIC_METADATA' $PublicMetadata
Set-EnvValue $envPath 'APP_UPDATE_PUBLIC_DOWNLOAD' $PublicDownload

Write-Output "Remote publish complete: $versionedApk"
PSLOGIC
)"

  # cmd.exe caps a command line at ~8191 characters and the encoded script is
  # larger than that, so upload it and keep the command line to a bootstrap that
  # just runs and deletes it. A BOM is deliberate here: PowerShell 5.1 reads a
  # .ps1 without one as ANSI rather than UTF-8.
  ps_local="$(mktemp)"
  {
    printf '\xEF\xBB\xBF'
    printf '%s\n%s\n' "$ps_values" "$ps_logic"
  } > "$ps_local"
  remote_ps="lexicon-publish-${VERSION_CODE}-$$.ps1"
  scp -P "$PORT" "$ps_local" "$USER@$HOST:~/$remote_ps"
  rm -f "$ps_local"

  ps_bootstrap="$(printf '%s\n' \
    "\$ErrorActionPreference = 'Stop'" \
    "\$s = Join-Path \$HOME '$remote_ps'" \
    "try { & \$s } finally { Remove-Item -LiteralPath \$s -Force -ErrorAction SilentlyContinue }")"
  ps_b64="$(printf '%s\n' "$ps_bootstrap" | utf16le_b64)"

  ssh -p "$PORT" "$USER@$HOST" \
    "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $ps_b64"
fi

if [[ -n "$RESTART_CMD" ]]; then
  echo "Running remote restart command"
  ssh -p "$PORT" "$USER@$HOST" "$RESTART_CMD"
fi

echo "Publish complete."
echo "versionCode=$VERSION_CODE"
echo "versionName=$VERSION_NAME"
echo "sha256=$SHA256"
