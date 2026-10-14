# 開発マニュアル

Camera Silencer のビルド、実機テスト、ソース構成、リリース手順をまとめています。アプリの使い方と機種相性の報告手順は [README](README.md) を参照してください。

## ビルドとインストール

リポジトリのルートで実行します。Nix の開発環境に JDK、Gradle、Android SDK、ADB を含めています。

```sh
nix develop --command gradle :app:assembleDebug
nix develop --command adb install -r app/build/outputs/apk/debug/app-debug.apk
nix develop --command adb shell am start -n dev.serika.camerasilencer/.MainActivity
```

| 用途 | Gradle タスク | APK |
| --- | --- | --- |
| 開発用 | `:app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| 通常配布用 | `:app:assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk`（署名設定時は `app-release.apk`） |
| 検証用 | `:app:assembleDiagnostic` | `app/build/outputs/apk/diagnostic/app-diagnostic.apk` |

実機検証用の通知権限と使用状況アクセスは、ADB からも設定できます。通知権限のコマンドは Android 13 以降が対象です。

```sh
nix develop --command adb shell pm grant dev.serika.camerasilencer android.permission.POST_NOTIFICATIONS
nix develop --command adb shell appops set dev.serika.camerasilencer GET_USAGE_STATS allow
```

## 実機テスト

USB デバッグを許可した端末で、カメラを閉じ、着信音を通常モード、おやすみモードをオフにして実行します。

```sh
nix develop --command gradle :app:connectedDebugAndroidTest
```

着信音モードと音量の維持、消音中のモード変更、保存データからの音声復旧を検証します。テスト中に変更した着信音量とモードは、各テストの終了時に戻します。

診断機能のテスト：

```sh
nix develop --command gradle -PtestBuildType=diagnostic \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.serika.camerasilencer.DiagnosticSessionTest \
  :app:connectedDiagnosticAndroidTest
```

アプリのログ：

```sh
nix develop --command adb logcat -v time -s BootReceiver CameraGuardService AudioSilencer OutputMixGuard
```

## ソース構成

| ディレクトリ | 内容 |
| --- | --- |
| `app/src/main` | カメラ検知、消音処理、通常版の画面 |
| `app/src/standard` | 通常版の依存関係の設定 |
| `app/src/diagnostic` | 診断画面、イベント記録、検証時間の管理、検証版の依存関係の設定 |
| `app/src/androidTest` | 音声設定の復元テスト |
| `app/src/androidTestDiagnostic` | 診断記録と検証の開始・終了のテスト |
| `fastlane/metadata/android` | 各言語のストア掲載文と変更履歴 |

`CameraManager.AvailabilityCallback` でカメラ使用を検知し、使用状況アクセスの許可時は `UsageStatsManager` で前面アプリを確認します。消音処理ではシステム系ストリームのミュートと Equalizer／DynamicsProcessing による減衰を併用し、カメラ使用中に無音の AudioTrack を再生します。監視にはフォアグラウンドサービスを使います。

通常版と検証版は `GuardObserver` と `GuardSession` を通して処理を注入します。ビルド時に各ディレクトリの `AppDependencies` が選ばれ、画面・記録・サービスの動作を決めます。

翻訳は `app/src/main/res/` 配下の `values/strings.xml`（英語）、`values-ja/strings.xml`（日本語）、`values-ko/strings.xml`（韓国語）で管理します。検証版の文言は `app/src/diagnostic/res/` にあります。追加時は各言語のリソース名、書式引数の番号と型を揃えます。対応言語の一覧は `app/src/main/res/xml/locales_config.xml` で管理します。

## リリース

### 通常版

`app/build.gradle` の `versionName` と `versionCode`、各言語の `changelogs/<versionCode>.txt`、`fdroid/metadata/dev.serika.camerasilencer.yml` を更新します。動作確認後、リリース対象のコミットに `v<version>` タグを付けて push します。

`Android` ワークフローは `main` への push と pull request で製品版をビルドします。`v*` タグの push 時には、APK と SHA-256 ファイルを GitHub Releases に公開します。Actions の手動実行では `release_tag` に公開対象のタグを指定できます。実行元にも同じタグを選んでください。ビルドだけを行う場合は `release_tag` を空にします。

通常版の署名設定：

| 項目 | ローカルの環境変数 | GitHub Actions のシークレット |
| --- | --- | --- |
| 署名鍵 | `ANDROID_KEYSTORE_PATH` | `ANDROID_SIGNING_KEYSTORE_BASE64`（鍵ファイルを Base64 化） |
| キーストアのパスワード | `ANDROID_KEYSTORE_PASSWORD` | `ANDROID_SIGNING_STORE_PASSWORD` |
| 鍵の別名 | `ANDROID_KEY_ALIAS` | `ANDROID_SIGNING_KEY_ALIAS` |
| 鍵のパスワード | `ANDROID_KEY_PASSWORD` | `ANDROID_SIGNING_KEY_PASSWORD` |

標準の構成では未署名 APK を生成し、F-Droid がソースから製品版をビルドして署名します。GitHub で直接インストール用の APK を配布する場合は、上記4項目を設定します。

### 検証版

製品版と同じタグをチェックアウトし、配布用署名を指定してビルドします。

```sh
nix develop --command gradle :app:assembleDiagnostic
```

生成した APK を `camera-silencer-<version>-diagnostic.apk` に改名し、SHA-256 ファイルとともに同じ GitHub Release に添付します。検証版の番号は `app/build.gradle` の `versionNameSuffix` で管理します。

検証版の署名設定：

| 項目 | ローカルの環境変数 | GitHub Actions のシークレット |
| --- | --- | --- |
| 署名鍵 | `DIAGNOSTIC_KEYSTORE_PATH` | `DIAGNOSTIC_SIGNING_KEYSTORE_BASE64`（鍵ファイルを Base64 化） |
| キーストアのパスワード | `DIAGNOSTIC_KEYSTORE_PASSWORD` | `DIAGNOSTIC_SIGNING_STORE_PASSWORD` |
| 鍵の別名 | `DIAGNOSTIC_KEY_ALIAS` | `DIAGNOSTIC_SIGNING_KEY_ALIAS` |
| 鍵のパスワード | `DIAGNOSTIC_KEY_PASSWORD` | `DIAGNOSTIC_SIGNING_KEY_PASSWORD` |

ローカルの初期設定は開発用署名です。配布時は専用の鍵を指定し、以降の更新でも同じ鍵を使います。開発用署名から配布用署名へ切り替えるときは、診断情報を保存して検証版を削除し、配布版を入れ直してください。

独立したプレリリースには `diagnostic-*` タグと「検証版」ワークフローを使えます。事前に GitHub Actions の配布用署名4項目を設定します。

### F-Droid

F-Droid は Git タグから更新を検出し、ソースから通常版をビルド・署名して公開します。

設定の原本は [fdroiddata の登録ファイル](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/dev.serika.camerasilencer.yml)です。リポジトリ内の `fdroid/metadata/dev.serika.camerasilencer.yml` は、変更提案用のひな形として管理します。

診断用の独立タグを公開する場合は、F-Droid 本家にも製品版タグの検出条件を反映します。

```yaml
UpdateCheckMode: Tags ^v[0-9]+(\.[0-9]+)*$
```

設定の詳細は [F-Droid の UpdateCheckMode](https://f-droid.org/docs/Build_Metadata_Reference/#updatecheckmode) を参照してください。
