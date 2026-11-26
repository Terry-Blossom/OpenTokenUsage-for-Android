[English](README.md) | **日本語**

# OpenTokenUsage for Android

[PowerUserZ/OpenTokenUsage](https://github.com/PowerUserZ/OpenTokenUsage) を参考に作成した、AI サービスの利用枠・クレジット残量を確認できる Android アプリおよびホーム画面ウィジェットです。

## 主な機能

- **初期データなし（ダミーデータ非搭載）**: 起動直後は空の状態で始まり、自分で追加したプロバイダーだけが表示されます。
- **Jetpack Glance ホーム画面ウィジェット**:
  - 一覧ウィジェット（複数プロバイダーをまとめて表示）
  - 単体ウィジェット（指定したアカウント 1 件を大きく表示・設定画面付き）
  - 通知バーと同じ表示の 1×3 ステータスウィジェット
- **アプリ内アップデート**: GitHub Releases を確認し、最新 APK をアプリ内からダウンロード・インストールできます。
- **各種プロバイダーの直接取得**: API キー / OAuth / セッション認証情報の入力に対応し、認証情報は AndroidX Security Crypto（`EncryptedSharedPreferences`）で端末内に暗号化保存します。
- **Cursor X サブスク対応**: 新しい X サブスクプランでは共有の週次プールのみを表示し、旧プラン向けの Plan usage・On-demand・Grok Bot は非表示にします。
- **通知バー常時表示**: 最新の使用率を通知バーに常時表示できます（設定でオン/オフ）。更新はバックグラウンドの自動更新に従います。
- **月の使用量表示（オプション）**: 月間利用枠があるプロバイダーについて、通知バー常時表示と 1×3 ステータスウィジェットの週表示の下に月表示を小さく追加できます（設定でオン/オフ、既定オフ）。
- **フォアグラウンド / バックグラウンド更新**:
  - **5分ごと（アプリ表示中）**: アプリ表示中（ライフサイクル `STARTED`）は 5 分ごとに自動更新し、バックグラウンド時は 15 分間隔で更新します。
  - **スマート更新**: アプリ表示中にアカウントごとで更新間隔を自動調整します。前回から使用量（`Progress` の used/limit）が変化していれば次回 **2 分**、変化なしが続くと **2→5→10→15 分**へ段階的に延長します。取得失敗時は別系統のバックオフ（**2→5→10→15 分**）を適用し、既存のレート制限ゲート（`RateLimitGateStore`）も尊重します。
  - **Android のバックグラウンド制約**: バックグラウンド定期更新は `WorkManager` を使用しているため、Android の仕様上 **最短 15 分間隔** となります（15 分未満の 5 分ごと・スマート更新の短間隔はアプリ表示中に動作し、バックグラウンド時は 15 分間隔で動作します）。
  - リセット周期に対する消費ペース計算（`PaceCalculator`）にも対応しています。

## 対応プロバイダー

| プロバイダー | 認証・接続方式 | 備考 |
| --- | --- | --- |
| Claude | OAuth トークン / `~/.claude/.credentials.json` | 5時間枠 / 週間枠・リセット回数 |
| Codex | OAuth トークン / `~/.codex/auth.json` | セッション / 週間 / Business 月間上限 |
| Cursor | `crsr_` API キー / Access Token / Session Cookie | Plan / API usage / ボーナス残高 |
| GitHub Copilot | GitHub PAT / `gh auth token` | カスタムホスト対応 |
| Antigravity | Google ログイン / OAuth トークン JSON | OAuth クライアント情報を端末内暗号化保存 |
| OpenRouter | API キー | API キー上限・クレジット残高 |
| DeepSeek | API キー | 残高・ステータス |
| Z.ai | API キー | 5時間枠 / 週間枠 / Web 検索枠 |
| MiniMax | API キー | Global / China リージョン切替対応 |
| Synthetic | API キー | 5時間枠 / 週間 / サブスク / 検索 |
| OpenCode Go | API キー | Session / Weekly / Monthly |
| Amp | API キー | Amp Free 残高・個別クレジット |
| Kimi Code | OAuth トークン / `kimi-code.json` | 5時間枠 / 週間 / 月間合計 |
| Kimi API | Moonshot API キー (`MOONSHOT_API_KEY`) | グローバル (`.ai`, `$`) / 中国 (`.cn`, `¥`) 自動判定 |
| Kilo | API キー / `auth.json` | クレジットブロック・Kilo Pass |
| Codebuff | API キー / `credentials.json` | クレジット残量・週間リミット |
| Grok | OAuth / `crsr_` API キー | クォータ / Grok Bot 週間枠 |
| Muse Code | OAuth トークン / `auth.json` | クォータ枠 |
| Factory / Droid | OAuth トークン / `auth.json` | クォータ枠 |
| Devin | API キー / `credentials.toml` | 日次 / 週間 / プロンプトクレジット |
| Kiro | OAuth トークン / `kiro-auth-token.json` | クォータ枠 |
| Custom balances | ベース URL + API キー（暗号化保存・複数登録可） | New API / One API（アカウント・API キー）、Sub2API、独自 GET ドットパス |
| Ollama | LAN サーバー URL（認証不要、例 `http://192.168.x.x:11434`） | バージョン・インストール済みモデル・読み込み中モデル（`OLLAMA_HOST=0.0.0.0:11434`） |

> 補足: PC 上のローカルファイルに依存し HTTP API を持たないプロバイダー（JetBrains AI Assistant の `AIAssistantQuotaManager2.xml`、Qwen Code の `~/.qwen/usage/*.jsonl` ローカル台帳など）は Android 版では非対応です。

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

### Antigravity の Google OAuth ログイン

「プロバイダー追加 → Antigravity」で Google OAuth Client ID と Client Secret を入力して保存し、「Google でログイン」をタップします。認証情報は端末内に暗号化保存されます。OAuth クライアントにはアプリに表示されるリダイレクト URI を許可してください。

ビルド時に `local.properties`・Gradle プロパティ・環境変数を指定すると、入力欄に初期値として反映できます:

- `antigravityOAuthClientId` / `ANTIGRAVITY_OAUTH_CLIENT_ID`
- `antigravityOAuthClientSecret` / `ANTIGRAVITY_OAUTH_CLIENT_SECRET`

### Antigravity の OAuth クライアント資格情報の取得方法

独自の Google Cloud OAuth クライアントを新規作成しないでください。Cloud Code Private API は Antigravity 自身のプロジェクトでのみ有効化されており、独自クライアントでは `403 Cloud Code Private API has not been used in project ...` になります。代わりに、インストール済み Antigravity アプリに埋め込まれた OAuth クライアント資格情報を使用してください。

インストール済み Antigravity から抽出します（パスは環境に合わせて調整）:

```powershell
# Client ID（app.asar と language_server.exe に含まれます）
Select-String -Path "C:\Users\<you>\AppData\Local\Programs\antigravity\resources\app.asar" -Pattern "apps\.googleusercontent\.com" | Select-Object -First 1

# Client Secret（language_server.exe に含まれます）
$exe = "C:\Users\<you>\AppData\Local\Programs\antigravity\resources\bin\language_server.exe"
$bytes = [System.IO.File]::ReadAllBytes($exe)
$text = [System.Text.Encoding]::ASCII.GetString($bytes)
[regex]::Matches($text, "GOCSPX-[A-Za-z0-9_-]+") | Select-Object -ExpandProperty Value -Unique
```

バイナリ内で Antigravity のクライアント ID の近くに現れる値を使用してください。両方の値を「プロバイダー追加 → Antigravity」に入力して保存し、「Google でログイン」をタップします。
