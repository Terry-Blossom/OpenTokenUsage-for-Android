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
