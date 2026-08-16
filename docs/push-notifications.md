# Push notifications (FCM)

The Android side is complete. Push stays dormant until Firebase is configured and
the backend endpoints exist — the app builds and runs fine in the meantime.

## What already works in the app

- `push/LexiconMessagingService` receives pushes and posts them to the system shade.
- `push/LexiconNotifier` owns the `lexicon_alerts` channel and builds the notification.
- `push/PushTokenRegistrar` reads the FCM token and sends it to the backend.
- `MainActivity` requests `POST_NOTIFICATIONS` on Android 13+ and syncs the token each launch.
- The in-app unread badge updates from the same push, via `NotificationRepository`.

## Step 1 — Firebase project (you must do this)

1. Create a Firebase project at <https://console.firebase.google.com>.
2. Add an Android app with package name `com.alexdyakin.lexicon`.
3. Download `google-services.json` and place it at `app/google-services.json`.

The `google-services` Gradle plugin is applied **only when that file exists**
(see `app/build.gradle.kts`). Without it the app still builds; push is simply inert
and `PushTokenRegistrar` logs that Firebase is not configured.

`app/google-services.json` should be added to `.gitignore` if you consider the
project config sensitive; it is not a secret, but it is environment-specific.

### CI

The publish workflow builds from a clean checkout, so it will produce a build with
push disabled unless `google-services.json` is provided. To enable it in CI, add the
file's base64 as a secret and write it out before the build step, the same way the
release keystore is handled in `.github/workflows/publish-android-update.yml`.

## Step 2 — Backend endpoints (not yet implemented)

The app calls two endpoints that do not exist yet. Both take:

```json
{ "userId": 1, "token": "<fcm-token>", "platform": "android" }
```

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/notifications/device-token` | Store or refresh a device token |
| `DELETE` | `/api/notifications/device-token` | Remove a token on sign-out |

Storage needs one row per `(userId, token)`; a user may have several devices, and the
same device can be reassigned to another user. Tokens rotate, so upsert on `token`.

## Step 3 — Sending from the backend

Use the Firebase Admin SDK with a service account key from the same Firebase project.

**Send `data` messages, not `notification` messages.** A `notification` payload is
rendered directly by the system and never reaches `onMessageReceived` while the app
is in the foreground, which would leave the in-app badge out of sync with the shade.
A `data` message always reaches the app.

Expected keys, matching what `LexiconMessagingService` reads:

```
id            the notification's database id (used as the OS notification id)
type          notification type
title         shown as the notification title
body          shown as the notification text
fromUsername  optional attribution
link          optional in-app route to open on tap
```

Send to every token registered for the target user, and delete tokens that come back
`UNREGISTERED` or `INVALID_ARGUMENT`.

## Step 4 — Where this fits the existing notification flow

Notifications already reach the app over SSE (`api/notifications/stream`) while it is
open; that is what drives the live badge today. FCM covers the case SSE cannot: the
app being backgrounded or killed. Both paths funnel into `NotificationRepository`, so
the unread count stays correct regardless of which delivered first — pushes are
de-duplicated by notification id.

## Testing without the backend

Once `google-services.json` is in place, send a test message from the Firebase
console (Cloud Messaging → send test message) using the token logged by
`PushTokenRegistrar` under the `PushTokenRegistrar` tag.
