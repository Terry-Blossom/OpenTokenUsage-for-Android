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
