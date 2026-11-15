**English** | [日本語](README.ja.md)

# OpenTokenUsage for Android

Android companion app and home-screen widgets inspired by [PowerUserZ/OpenTokenUsage](https://github.com/PowerUserZ/OpenTokenUsage).

## Features

- **Zero pre-populated / mock data**: Starts completely empty until you add your own providers or connect a desktop instance.
- **Jetpack Glance Home-Screen Widgets**:
  - Overview widget (multiple providers at a glance)
  - Single-provider widget (configurable per account)
- **Desktop Sync**: Connect to the PC OpenTokenUsage Local HTTP API (`:6736`, e.g. over Tailscale + Bearer token proxy) to mirror desktop-only or local-CLI providers on Android.
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

### Optional: Antigravity OAuth Token Refresh

OAuth client credentials are not embedded in the repository. To enable automatic Google OAuth refresh for Antigravity, pass Gradle properties or environment variables at build time:

- `antigravityOAuthClientId` / `ANTIGRAVITY_OAUTH_CLIENT_ID`
- `antigravityOAuthClientSecret` / `ANTIGRAVITY_OAUTH_CLIENT_SECRET`

## Desktop Sync Setup (PC OpenTokenUsage + Tailscale)

Desktop OpenTokenUsage automatically starts its Local HTTP API on `127.0.0.1:6736`, and rejects non-loopback `Host` headers with `403 Forbidden`. To read it from Android over Tailscale:

1. **Run a lightweight loopback proxy on your PC** (e.g., Node.js listening on `127.0.0.1:16736` -> forwarding `GET /v1/usage*` to `127.0.0.1:6736`):
   - Rewrite `Host` header to `127.0.0.1:6736` (and strip `Origin`).
   - Require `Authorization: Bearer <YOUR_TOKEN>` (`401` otherwise).
   - Allow read-only `GET /v1/usage` and `GET /v1/usage/:providerId` only.

```js
// Minimal example: PROXY_TOKEN=your-secret-token node proxy.mjs
import http from 'node:http';

const TOKEN = process.env.PROXY_TOKEN;
if (!TOKEN) throw new Error('Set PROXY_TOKEN');

http.createServer((req, res) => {
  if (req.headers.authorization !== `Bearer ${TOKEN}`) {
    res.writeHead(401).end('{"error":"unauthorized"}');
    return;
  }
  const path = (req.url || '/').split('?')[0].replace(/\/+$/, '') || '/';
  if (req.method !== 'GET' || !(path === '/v1/usage' || /^\/v1\/usage\/[a-z0-9-]+$/i.test(path))) {
    res.writeHead(404).end('{"error":"not_found"}');
    return;
  }
  const up = http.request(
    { host: '127.0.0.1', port: 6736, path, method: 'GET', headers: { Host: '127.0.0.1:6736', Accept: 'application/json' } },
    (r) => { res.writeHead(r.statusCode || 502, { 'Content-Type': 'application/json' }); r.pipe(res); },
  );
  up.on('error', () => res.writeHead(502).end('{"error":"upstream_unavailable"}'));
  up.end();
}).listen(16736, '127.0.0.1');
```

2. **Expose it only to your tailnet via Tailscale Serve**:

```bash
tailscale serve --bg --https=6736 http://127.0.0.1:16736
```

3. **Configure the Android app**:
   - Add provider -> **Desktop Sync (`PC版 OpenTokenUsage 同期`)**
   - **URL**: `https://<your-device>.ts.net:6736`
   - **Bearer Token**: `<YOUR_TOKEN>`
