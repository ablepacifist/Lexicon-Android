# Publish Android Update Workflow

This document describes what the `Publish Android Update` GitHub Actions workflow is supposed to do and which GitHub secrets it expects.

## What the workflow should do

1. Check out the `LexiconAndroid` repository.
2. Set up Java 21.
3. Restore the release signing keystore from a GitHub secret.
4. Build the Android release APK.
5. Prepare the SSH private key used for deployment.
6. If Cloudflare deploy mode is enabled, start a local `cloudflared access tcp` proxy to the SSH host.
7. Add the target host to `known_hosts`.
8. Upload the release APK to the deployment host.
9. Update the remote `.env` file with the latest app update metadata.
10. Optionally run the remote restart command after the upload completes.

## Workflow inputs

These are entered when you click **Run workflow**:

- `APP_UPDATE_VERSION_CODE`
  - Integer release code used by the app to compare versions.
- `APP_UPDATE_VERSION_NAME`
  - Human-readable version string, such as `0.5.0`.
- `APP_UPDATE_CHANGELOG`
  - Optional release notes shown in the app.
- `APP_UPDATE_CRITICAL`
  - `true` or `false` depending on whether the update should be treated as required.

## GitHub secrets used by the workflow

### Android signing

- `APP_RELEASE_KEYSTORE_BASE64`
  - Base64-encoded release keystore file.
- `APP_RELEASE_KEYSTORE_PASSWORD`
  - Password for the keystore.
- `APP_RELEASE_KEY_ALIAS`
  - Alias inside the keystore.
- `APP_RELEASE_KEY_PASSWORD`
  - Password for the key alias.

### Deploy and SSH

- `DEPLOY_SSH_KEY`
  - Private SSH key used by GitHub Actions to connect to the deployment host.
- `DEPLOY_SSH_KEY_PASSPHRASE`
  - Optional. Only needed when `DEPLOY_SSH_KEY` is passphrase-protected. The workflow uses it to strip the passphrase, because `ssh`/`scp` run non-interactively and cannot prompt for one.
- `DEPLOY_HOST`
  - SSH host or Cloudflare hostname.
- `DEPLOY_PORT`
  - SSH port, usually `22`.
- `DEPLOY_USER`
  - SSH user on the deployment machine.
- `DEPLOY_TARGET_OS`
  - Target host OS, such as `windows` or `linux`.
- `DEPLOY_RELEASE_DIR`
  - Directory on the target machine where the APK should be stored.
- `DEPLOY_ENV_PATH`
  - Path to the remote `.env` file that stores app update values.
- `DEPLOY_RESTART_CMD`
  - Command used to restart services after publishing.

### Cloudflare tunnel mode

- `DEPLOY_USE_CLOUDFLARE`
  - Set to `true` to route SSH through Cloudflare Access.
- `CF_ACCESS_CLIENT_ID`
  - Cloudflare Access service token client ID.
- `CF_ACCESS_CLIENT_SECRET`
  - Cloudflare Access service token client secret.

### App update publication values

- `APP_UPDATE_DOWNLOAD_URL`
  - Public download URL exposed to the app.
- `APP_UPDATE_PUBLIC_METADATA`
  - Whether the version metadata endpoint is public.
- `APP_UPDATE_PUBLIC_DOWNLOAD`
  - Whether the APK download endpoint is public.

## Expected secret handling

- `DEPLOY_SSH_KEY` must be a valid OpenSSH private key.
- The key should be stored as a multiline secret if possible.
- The workflow normalizes common formatting issues before use:
  - CRLF line endings
  - a UTF-8 or UTF-16 byte-order mark, and UTF-16 encoding
  - leading indentation or trailing whitespace on each line
  - the key wrapped in single or double quotes
  - escaped `\n` sequences instead of real newlines
  - stray blank lines, and a missing newline after the final `-----END-----` line
  - a key that was base64-encoded as a single blob
  - a paste that dropped the `-----BEGIN-----` / `-----END-----` lines, leaving only
    the base64 body (the armor is re-attached when the body decodes as an OpenSSH key)

### Setting `DEPLOY_SSH_KEY` from Windows

PowerShell 5.1 writes UTF-8 **with a BOM** by default, and a BOM at the start of the
key makes OpenSSH reject it. Pass the file contents as a single raw string:

```powershell
gh secret set DEPLOY_SSH_KEY --repo <owner>/<repo> --body (Get-Content -Raw $HOME\.ssh\lexicon_github_actions)
```

Note that `<` input redirection is not available in PowerShell 5.1. Avoid writing the
key to an intermediate file with `Out-File` or `Set-Content`, which add a BOM. The
workflow strips BOMs and CRLF endings now, but it is cleaner not to introduce them.
- A passphrase-protected key requires `DEPLOY_SSH_KEY_PASSPHRASE`; the workflow fails with an explicit message if the passphrase is missing.
- A PuTTY `.ppk` will not work. Convert it first: `puttygen key.ppk -O private-openssh -o id_deploy`.
- If key loading fails, the workflow prints structural diagnostics (byte/line counts, BEGIN/END markers) without ever printing key material.
- Do not place actual secret values in this repository.

## Typical run values

Example manual run:

- `APP_UPDATE_VERSION_CODE`: `5`
- `APP_UPDATE_VERSION_NAME`: `0.5.0`
- `APP_UPDATE_CHANGELOG`: `Bug fixes and deployment updates`
- `APP_UPDATE_CRITICAL`: `false`

## Notes

- The workflow builds the Android APK from the `main` branch of `LexiconAndroid`.
- The deployment path may differ depending on whether the target host is Windows or Linux.
- Cloudflare mode uses an SSH proxy and should only be enabled when the Cloudflare Access app and service token are configured correctly.