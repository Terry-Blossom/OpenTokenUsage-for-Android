# リリース手順（RELEASING）

このリポジトリでは、CI（GitHub Actions）で署名鍵や Secret を保持せず、次の 2 段階でリリースを作成します。

1. `v*` タグの push をトリガーに、GitHub Actions（`.github/workflows/release.yml`）がテストと未署名 release APK（R8 minify 有効）をビルドして GitHub Release を自動作成する。
2. 既存インストール環境と同一の署名で上書きアップデートできるよう、ローカル環境（ボックス）で**既存の署名鍵**を使って `apksigner` で署名した APK と更新版 `SHA256SUMS.txt` を同じリリースへ追加アップロードする。

---

## 1. リリース準備・タグ push

1. `app/build.gradle.kts` の `versionCode` と `versionName` を更新し、`main` にコミットします。
2. ローカルでテストと debug / release ビルドが通ることを確認します。
3. タグを作成して push します（例: `v0.3.0`）。

```bash
git tag v0.3.0
git push origin main --tags
```

---

## 2. CI リリース作成の確認

`v*` タグを push すると `.github/workflows/release.yml` が自動実行されます。

- 実行環境: `ubuntu-latest` / JDK 17 (Temurin)
- 実行コマンド: `./gradlew test assembleRelease`
- 生成・添付されるファイル:
  - `OpenTokenUsage-<ver>-release-unsigned.apk`（未署名・minify 有効）
  - `SHA256SUMS.txt`
- リリース本文には自動生成されたリリースノートに加えて「署名済み APK は後から追加されます。」が記載されます。

---

## 3. ボックスでの署名と追加アップロード

CI が作成したリリースから未署名 APK と `SHA256SUMS.txt` を取得し、**既存の署名鍵**で署名した `OpenTokenUsage-<ver>.apk` を追加します。

```bash
VER="0.3.0"
TAG="v${VER}"
WORKDIR="$(mktemp -d)"
cd "${WORKDIR}"

# 1. リリース資産をダウンロード
gh release download "${TAG}" \
  -p "OpenTokenUsage-${VER}-release-unsigned.apk" \
  -p "SHA256SUMS.txt"

# 2. 既存の署名鍵を使って apksigner で署名
apksigner sign \
  --ks "<既存の署名鍵>" \
  --out "OpenTokenUsage-${VER}.apk" \
  "OpenTokenUsage-${VER}-release-unsigned.apk"

# 3. 署名を検証
apksigner verify --verbose "OpenTokenUsage-${VER}.apk"

# 4. SHA256SUMS.txt に署名済み APK のチェックサムを追記して更新
grep -v " OpenTokenUsage-${VER}.apk$" SHA256SUMS.txt > SHA256SUMS.txt.tmp || true
sha256sum "OpenTokenUsage-${VER}.apk" >> SHA256SUMS.txt.tmp
mv SHA256SUMS.txt.tmp SHA256SUMS.txt

# 5. 署名済み APK と更新版 SHA256SUMS.txt をリリースに追加アップロード
gh release upload "${TAG}" \
  "OpenTokenUsage-${VER}.apk" \
  "SHA256SUMS.txt" \
  --clobber
```

---

## 4. アプリ内アップデート（AppUpdater）の動作

- `AppUpdater` は最新リリースの資産一覧から名前に `unsigned` を含む APK を除外し、`OpenTokenUsage-` で始まる `.apk` を優先して選択します（無ければその他の `.apk`）。
- そのため、CI 完了直後（未署名 APK のみの状態）は自動更新対象にならず、手順 3 で署名済み `OpenTokenUsage-<ver>.apk` を追加アップロードした時点でアプリ内アップデートから上書きインストール可能になります。
