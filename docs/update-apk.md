# Lexicon Android Update Guide

This project uses a private APK self-update flow. The Android app checks the Lexicon backend for the latest version, downloads the APK from lexiconServer, and launches the installer.

## What lives where

- `LexiconAndroid/.github/workflows/publish-android-update.yml` is the GitHub Actions publish workflow.
- `LexiconAndroid/scripts/publish-android-update.sh` uploads the APK and updates the remote server metadata.
- `full-back-end-server/.env` is the real shared runtime config on the server machine.
- `lexiconServer/.env.example` is only a template for new setups.

## The important env split

There are two different env layers:

1. Server runtime env on the machine that hosts the whole stack.
  - This is the real shared `.env` file at the root of the deployment checkout.
  - It controls where the APK is stored and what metadata the app sees.
  - In this workspace, that file is [full-back-end-server/.env](../.env).
   - Example keys:
     - `APP_UPDATE_VERSION_CODE`
     - `APP_UPDATE_VERSION_NAME`
     - `APP_UPDATE_DOWNLOAD_URL`
     - `APP_UPDATE_APK_PATH`
     - `APP_UPDATE_CRITICAL`
     - `APP_UPDATE_CHANGELOG`
     - `APP_UPDATE_SHA256`
     - `APP_UPDATE_PUBLIC_METADATA`
     - `APP_UPDATE_PUBLIC_DOWNLOAD`

2. GitHub Secrets for CI.
   - These are only for the publish workflow.
   - They are not the server runtime config.
   - Needed secrets for release publishing:
     - `DEPLOY_HOST`
     - `DEPLOY_USER`
     - `DEPLOY_PORT`
     - `DEPLOY_RELEASE_DIR`
     - `DEPLOY_ENV_PATH`
     - `DEPLOY_RESTART_CMD`
     - `APP_RELEASE_KEYSTORE_BASE64`
     - `APP_RELEASE_KEYSTORE_PASSWORD`
     - `APP_RELEASE_KEY_ALIAS`
     - `APP_RELEASE_KEY_PASSWORD`
     - `APP_UPDATE_DOWNLOAD_URL`
     - `APP_UPDATE_PUBLIC_METADATA`
     - `APP_UPDATE_PUBLIC_DOWNLOAD`

## Release process

1. Build the signed release APK with GitHub Actions.
2. The workflow uploads the APK to the remote release directory.
3. The workflow updates the remote root `.env` with the new version metadata and checksum.
4. The workflow refreshes the `lexicon-latest.apk` symlink and restarts the stack if a restart command is configured.
5. The Android app checks `/api/app/version`, downloads the new APK, and installs it.

## Manual release steps

If you are not using GitHub Actions, do this on your workstation:

1. Build the release APK.
2. Compute SHA-256 for the APK.
3. Copy the APK to the server release directory.
4. Update the real shared root `.env` on the server with the new version values.
5. Ensure `APP_UPDATE_DOWNLOAD_URL` points at `https://api.alex-dyakin.com/api/app/download/latest`.
6. Restart the stack with the root `restart-all.sh` script.

## Notes

- The app must be installed with the same signing key as the release APK you publish.
- If the app is already installed as debug-signed, update it with a debug-signed APK only. For production, use release signing consistently.
- `APP_UPDATE_PUBLIC_METADATA=false` and `APP_UPDATE_PUBLIC_DOWNLOAD=false` keep the update flow private behind auth.
- A practical `DEPLOY_RESTART_CMD` is `cd /home/alexpdyak32/Documents/lexicon/full-back-end-server && ./restart-all.sh` on the Linux host, or the equivalent path to your root checkout.
