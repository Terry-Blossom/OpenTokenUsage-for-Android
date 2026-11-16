**English** | [日本語](README.ja.md)

# OpenTokenUsage for Android

Android companion app and home-screen widgets inspired by [PowerUserZ/OpenTokenUsage](https://github.com/PowerUserZ/OpenTokenUsage).

## Features

- **Zero pre-populated / mock data**: Starts completely empty until you add your own providers.
- **Jetpack Glance Home-Screen Widgets**:
  - Overview widget (multiple providers at a glance)
  - Single-provider widget (configurable per account)
- **Direct Provider Support**: Supports direct API key / OAuth / session credential entry and encrypted local storage via AndroidX Security Crypto (`EncryptedSharedPreferences`).
- **Cursor X Subscription aware**: On the new Cursor X Subscription plans, only the shared weekly pool (`Total usage`) is shown; legacy plan, on-demand, and Grok Bot lines are hidden.
- **Persistent notification**: Optional ongoing notification in the status bar with the latest usage per account, refreshed by the background schedule.
- **Background Refresh**: Periodic updates powered by `WorkManager` with pace calculation (`PaceCalculator`).

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- Jetpack Glance (`androidx.glance:glance-appwidget`)
- WorkManager + DataStore + AndroidX Security Crypto
- OkHttp + `kotlinx.serialization`
- `minSdk = 26` / `targetSdk = 35` / `compileSdk = 35`

## Build & Test

Requires JDK 17 and Android SDK (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Antigravity Google OAuth Login

Open **Add provider → Antigravity**, enter your Google OAuth Client ID and Client Secret, save them, then tap **Google Login**. The credentials are encrypted on-device. The OAuth client must allow the redirect URI shown in the app.

You can prefill the fields at build time using `local.properties`, Gradle properties, or environment variables:

- `antigravityOAuthClientId` / `ANTIGRAVITY_OAUTH_CLIENT_ID`
- `antigravityOAuthClientSecret` / `ANTIGRAVITY_OAUTH_CLIENT_SECRET`
