[English](README.md) | **日本語**

# OpenTokenUsage for Android

[PowerUserZ/OpenTokenUsage](https://github.com/PowerUserZ/OpenTokenUsage) を参考に作成した、AI サービスの利用枠・クレジット残量を確認できる Android アプリおよびホーム画面ウィジェットです。

## 主な機能

- **初期データなし（ダミーデータ非搭載）**: 起動直後は空の状態で始まり、自分でプロバイダーまたは PC 同期先を追加した分だけ表示されます。
- **Jetpack Glance ホーム画面ウィジェット**:
  - 一覧ウィジェット（複数プロバイダーをまとめて表示）
  - 単体ウィジェット（指定したアカウント 1 件を大きく表示・設定画面付き）
- **PC版 OpenTokenUsage 同期（Desktop Sync）**: PC 版の Local HTTP API（既定ポート `6736`、Tailscale 等＋Bearer 認証プロキシ経由）に接続し、PC 側で計測中のプロバイダーを Android にミラー表示できます。
- **各種プロバイダーの直接取得**: API キー / OAuth / セッション認証情報の入力に対応し、認証情報は AndroidX Security Crypto（`EncryptedSharedPreferences`）で端末内に暗号化保存します。
- **バックグラウンド更新**: `WorkManager` による定期更新と、リセット周期に対する消費ペース計算（`PaceCalculator`）に対応しています。

## 技術スタック

- Kotlin + Jetpack Compose (Material 3)
- Jetpack Glance (`androidx.glance:glance-appwidget`)
- WorkManager + DataStore + AndroidX Security Crypto
- OkHttp + `kotlinx.serialization`
- `minSdk = 26` / `targetSdk = 35` / `compileSdk = 35`

## ビルド・テスト方法

JDK 17 と Android SDK（`ANDROID_HOME` または `local.properties`）が必要です。

```bash
./gradlew testDebugUnitTest assembleDebug
```

デバッグ APK の出力先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 補足: Antigravity の OAuth 自動リフレッシュ設定（任意）

OAuth クライアント情報はリポジトリに含めていません。Antigravity のトークン自動更新をビルドに含める場合は、ビルド時に Gradle プロパティまたは環境変数で指定してください（未指定時は PC 版同期または有効なアクセストークンを利用できます）。

- `antigravityOAuthClientId` / `ANTIGRAVITY_OAUTH_CLIENT_ID`
- `antigravityOAuthClientSecret` / `ANTIGRAVITY_OAUTH_CLIENT_SECRET`

## PC 版同期のセットアップ手順（Tailscale 経由）

PC 版 OpenTokenUsage の Local HTTP API（既定ポート `6736`）は仕様上 `127.0.0.1:6736` のみで待受け、`Host` ヘッダーが `127.0.0.1` / `localhost` 以外の場合は `403 Forbidden` を返します。そのため Android から接続する場合は、PC 側で以下の軽量プロキシを挟みます。

1. **PC 側で読み取り専用＋Bearer 認証プロキシを起動する**（`127.0.0.1:16736` → `127.0.0.1:6736`）:
   - `Host` ヘッダーを `127.0.0.1:6736` に書き換え（`Origin` は除去）
   - `Authorization: Bearer <任意のトークン>` を必須化
   - `GET /v1/usage` と `GET /v1/usage/:providerId` のみ許可

```js
// 最小構成例: PROXY_TOKEN=任意の長いランダム文字列 node proxy.mjs
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

2. **Tailscale Serve で tailnet 限定公開する**（既存の `:443` とは別ポートで共存可能）:

```bash
tailscale serve --bg --https=6736 http://127.0.0.1:16736
```

3. **Android アプリ側で登録する**:
   - プロバイダー追加 → **「PC版 OpenTokenUsage 同期」** を選択
   - **PC版 OpenTokenUsage URL**: `https://<あなたの端末名>.ts.net:6736`
   - **認証トークン（設定している場合のみ・任意）**: 手順 1 で設定した `<任意のトークン>`

※ Android 単体で完結するプロバイダー（OpenRouter / Claude / Codex / Cursor 等）のみを使う場合、PC 同期および Tailscale は不要です。
