**English** | [日本語](README.ja.md)

# OpenTokenUsage for Android

Android companion app and home-screen widgets inspired by [PowerUserZ/OpenTokenUsage](https://github.com/PowerUserZ/OpenTokenUsage).

## Features

- **Zero pre-populated / mock data**: Starts completely empty until you add your own providers.
- **Jetpack Glance Home-Screen Widgets**:
  - Overview widget (multiple providers at a glance)
  - Single-provider widget (configurable per account)
  - 1x3 status strip widget that mirrors the persistent notification layout
- **In-app updates**: Checks GitHub Releases and downloads/installs the latest APK without leaving the app.
- **Direct Provider Support**: Supports direct API key / OAuth / session credential entry and encrypted local storage via AndroidX Security Crypto (`EncryptedSharedPreferences`).
- **Cursor X Subscription aware**: On the new Cursor X Subscription plans, only the shared weekly pool (`Total usage`) is shown; legacy plan, on-demand, and Grok Bot lines are hidden.
- **Persistent notification**: Optional ongoing notification in the status bar with the latest usage per account, refreshed by the background schedule.
- **Optional monthly usage line**: Optionally shows a compact monthly usage line below the weekly percentage in the persistent notification and 1x3 status strip widget for providers with monthly quotas.
- **Background & Foreground Refresh**:
  - **5 minutes (while app is open)**: Refreshes every 5 minutes while the app is in the foreground (`STARTED`), and falls back to 15 minutes in the background.
  - **Smart refresh**: Adapts per account while the app is in the foreground — polls every **2 minutes** when usage (`Progress` used/limit) changes, backs off **2 → 5 → 10 → 15 minutes** while unchanged, uses a separate **2 → 5 → 10 → 15 minutes** failure backoff, and respects per-provider rate-limit gates (`RateLimitGateStore`).
  - **Android WorkManager constraint**: Background periodic sync runs via `WorkManager`, whose minimum interval on Android is **15 minutes** (so intervals shorter than 15 minutes apply while the app is displayed, and background sync runs at 15 minutes).
  - Includes pace calculation (`PaceCalculator`).

## Supported Providers

| Provider | Auth / Setup | Notes |
| --- | --- | --- |
| Claude | OAuth token / `~/.claude/.credentials.json` | 5h / 7d quotas, reset credits |
| Codex | OAuth token / `~/.codex/auth.json` | Session / Weekly / Business monthly |
| Cursor | `crsr_` API key / access token / session cookie | Plan / API usage / bonus credits |
| GitHub Copilot | GitHub PAT / `gh auth token` | Custom host supported |
| Antigravity | Google OAuth login / token JSON | Encrypted OAuth client credentials |
| OpenRouter | API key | Key limit & account credits |
| DeepSeek | API key | Wallet balance & status |
| Z.ai | API key | 5h / 7d / Web search limits |
| MiniMax | API key | Global / China region selector |
| Synthetic | API key | 5h / Weekly / Subscription / Search |
| OpenCode Go | API key | Session / Weekly / Monthly |
| Amp | API key | Free balance & individual credits |
| Kimi Code | OAuth token / `kimi-code.json` | 5h / Weekly / Monthly |
| Kimi API | Moonshot API key (`MOONSHOT_API_KEY`) | Global (`.ai`, `$`) / China (`.cn`, `¥`) auto-detection |
| Kilo | API key / `auth.json` | Credit blocks & Kilo Pass |
| Codebuff | API key / `credentials.json` | Credits & weekly limit |
| Grok | OAuth / `crsr_` API key | Quota / Grok Bot weekly |
| Muse Code | OAuth token / `auth.json` | Quota limits |
| Factory / Droid | OAuth token / `auth.json` | Quota limits |
| Devin | API key / `credentials.toml` | Daily / Weekly / prompt credits |
| Kiro | OAuth token / `kiro-auth-token.json` | Quota limits |
| Custom balances | Base URL + API key (encrypted, multiple accounts) | New API / One API (account & API key), Sub2API, custom GET dot-path endpoint |
| Ollama | LAN server URL (no auth, e.g. `http://192.168.x.x:11434`) | Server version, installed models, loaded models (`OLLAMA_HOST=0.0.0.0:11434`) |

> Note: Desktop-only providers that read local PC files rather than an HTTP API (such as JetBrains AI Assistant's `AIAssistantQuotaManager2.xml` and Qwen Code's local `~/.qwen/usage/*.jsonl` ledger) are not supported on Android.

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

### Obtaining Antigravity's OAuth client credentials

Do **not** create your own Google Cloud OAuth client. The Cloud Code Private API is only enabled for Antigravity's own project, so a custom client fails with `403 Cloud Code Private API has not been used in project ...`. Use the OAuth client credentials embedded in the installed Antigravity app instead.

Extract them from the Antigravity installation (adjust the path to your install):

```powershell
# Client ID (in app.asar and language_server.exe)
Select-String -Path "C:\Users\<you>\AppData\Local\Programs\antigravity\resources\app.asar" -Pattern "apps\.googleusercontent\.com" | Select-Object -First 1

# Client Secret (in language_server.exe)
$exe = "C:\Users\<you>\AppData\Local\Programs\antigravity\resources\bin\language_server.exe"
$bytes = [System.IO.File]::ReadAllBytes($exe)
$text = [System.Text.Encoding]::ASCII.GetString($bytes)
[regex]::Matches($text, "GOCSPX-[A-Za-z0-9_-]+") | Select-Object -ExpandProperty Value -Unique
```

Use the value that appears next to the Antigravity client id in the binary. Enter both values in **Add provider → Antigravity**, save, then tap **Google Login**.
