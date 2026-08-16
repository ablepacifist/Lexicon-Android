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

  # Send values as env vars and run PowerShell logic remotely.
  ssh -p "$PORT" "$USER@$HOST" \
    "set APP_UPDATE_VERSION_CODE=$VERSION_CODE && \
     set APP_UPDATE_VERSION_NAME=$VERSION_NAME && \
     set APP_UPDATE_DOWNLOAD_URL=$DOWNLOAD_URL && \
     set APP_UPDATE_APK_PATH=$APK_ENV_PATH && \
     set APP_UPDATE_CRITICAL=$CRITICAL && \
     set APP_UPDATE_CHANGELOG_B64=$CHANGELOG_B64 && \
     set APP_UPDATE_SHA256=$SHA256 && \
     set APP_UPDATE_PUBLIC_METADATA=$PUBLIC_METADATA && \
     set APP_UPDATE_PUBLIC_DOWNLOAD=$PUBLIC_DOWNLOAD && \
     set DEPLOY_RELEASE_DIR=$REMOTE_RELEASE_DIR && \
     set DEPLOY_ENV_PATH=$REMOTE_ENV_PATH && \
     set DEPLOY_VERSIONED_APK=$REMOTE_VERSIONED_APK && \
     set DEPLOY_TMP_NAME=$tmp_name && \
     powershell -NoProfile -NonInteractive -Command \"\
     \\\$ErrorActionPreference = 'Stop'; \
     function Resolve-DeployPath([string]\\\$p) { if ([System.IO.Path]::IsPathRooted(\\\$p)) { return \\\$p } else { return Join-Path \\\$HOME \\\$p } }; \
     function Set-EnvValue([string]\\\$file,[string]\\\$key,[string]\\\$value) { \
       if (Test-Path \\\$file) { \\\$lines = Get-Content \\\$file } else { \\\$lines = @() }; \
       \\\$pattern = '^' + [regex]::Escape(\\\$key) + '='; \
       \\\$found = \\\$false; \
       for (\\\$i=0; \\\$i -lt \\\$lines.Count; \\\$i++) { if (\\\$lines[\\\$i] -match \\\$pattern) { \\\$lines[\\\$i] = \\\"\\\$key=\\\$value\\\"; \\\$found = \\\$true; break } }; \
       if (-not \\\$found) { \\\$lines += \\\"\\\$key=\\\$value\\\" }; \
       Set-Content -Path \\\$file -Value \\\$lines -Encoding UTF8; \
     }; \
     \\\$releaseDir = Resolve-DeployPath \\\$env:DEPLOY_RELEASE_DIR; \
     \\\$envPath = Resolve-DeployPath \\\$env:DEPLOY_ENV_PATH; \
     \\\$versionedApk = Resolve-DeployPath \\\$env:DEPLOY_VERSIONED_APK; \
     \\\$latestApk = Resolve-DeployPath \\\$env:APP_UPDATE_APK_PATH; \
     \\\$tmpUploaded = Join-Path \\\$HOME \\\$env:DEPLOY_TMP_NAME; \
     New-Item -ItemType Directory -Path \\\$releaseDir -Force | Out-Null; \
     New-Item -ItemType Directory -Path (Split-Path -Parent \\\$versionedApk) -Force | Out-Null; \
     New-Item -ItemType Directory -Path (Split-Path -Parent \\\$latestApk) -Force | Out-Null; \
     Move-Item -Path \\\$tmpUploaded -Destination \\\$versionedApk -Force; \
     Copy-Item -Path \\\$versionedApk -Destination \\\$latestApk -Force; \
     New-Item -ItemType File -Path \\\$envPath -Force | Out-Null; \
     \\\$changelog = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String(\\\$env:APP_UPDATE_CHANGELOG_B64)); \
     Set-EnvValue \\\$envPath 'APP_UPDATE_VERSION_CODE' \\\$env:APP_UPDATE_VERSION_CODE; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_VERSION_NAME' \\\$env:APP_UPDATE_VERSION_NAME; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_DOWNLOAD_URL' \\\$env:APP_UPDATE_DOWNLOAD_URL; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_APK_PATH' \\\$env:APP_UPDATE_APK_PATH; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_CRITICAL' \\\$env:APP_UPDATE_CRITICAL; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_CHANGELOG' \\\$changelog; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_SHA256' \\\$env:APP_UPDATE_SHA256; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_PUBLIC_METADATA' \\\$env:APP_UPDATE_PUBLIC_METADATA; \
     Set-EnvValue \\\$envPath 'APP_UPDATE_PUBLIC_DOWNLOAD' \\\$env:APP_UPDATE_PUBLIC_DOWNLOAD; \
     \""
fi

if [[ -n "$RESTART_CMD" ]]; then
  echo "Running remote restart command"
  ssh -p "$PORT" "$USER@$HOST" "$RESTART_CMD"
fi

echo "Publish complete."
echo "versionCode=$VERSION_CODE"
echo "versionName=$VERSION_NAME"
echo "sha256=$SHA256"
