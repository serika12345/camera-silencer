# リポジトリの作業指示

このファイルは、Camera Silencer リポジトリで作業する開発支援エージェント向けの指示です。アプリの動作は [README.md](README.md)、開発・リリース手順は [DEVELOPMENT.md](DEVELOPMENT.md)、貢献時の要件は [CONTRIBUTING.md](CONTRIBUTING.md) を参照してください。

## プロジェクトの構成

Camera Silencer は、カメラ使用中に音声処理を行う Java 17 の Android アプリです。対応する最小の Android API レベルは 26 です。コンパイル対象と動作対象の API レベルは `app/build.gradle` に定義しています。

| パス | 用途 |
| --- | --- |
| `app/src/main` | カメラ検知、フォアグラウンドサービス、音声処理、通常版の画面 |
| `app/src/standard` | 開発用・通常配布用ビルドの `AppDependencies` |
| `app/src/diagnostic` | 診断画面、記録、検証の開始・終了の管理、検証版の `AppDependencies` |
| `app/src/androidTest` | 音声状態の維持・復旧を確認する端末上のテスト |
| `app/src/androidTestDiagnostic` | 診断記録と検証の開始・終了を確認する端末上のテスト |
| `.github` | Issue・PR テンプレート、ビルド・リリースのワークフロー |
| `fastlane/metadata/android` | ストア掲載文、翻訳、変更履歴 |
| `fdroid` | F-Droid の登録情報の変更案と提出手順 |

共通コードは `GuardSession` と `GuardObserver` を通じて、ビルドの種類ごとに異なる処理を呼び出します。これらのインターフェースを変更するときは、通常版と検証版の実装の整合性を保ってください。

## 開発と検証

[flake.nix](flake.nix) に、JDK、Gradle、Android SDK、ADB を含む開発シェルがあります。以下のコマンドはリポジトリのルートで実行します。

変更の影響を受ける種類のビルドを確認してください。共通コードやビルド設定を変更した場合は、通常配布用と検証用の両方を確認します。

```sh
nix develop --command gradle :app:assembleRelease :app:assembleDiagnostic
```

開発用ビルドのコマンドは次のとおりです。

```sh
nix develop --command gradle :app:assembleDebug
```

音声処理や診断機能の動作を変更した場合は、関連する端末上のテストを実行してください。

```sh
nix develop --command gradle :app:connectedDebugAndroidTest
nix develop --command gradle -PtestBuildType=diagnostic \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.serika.camerasilencer.DiagnosticSessionTest \
  :app:connectedDiagnosticAndroidTest
```

端末の準備とログの取得方法は [DEVELOPMENT.md](DEVELOPMENT.md#実機テスト) に記載しています。シャッター音の抑制効果は、端末で撮影して確認します。

## 維持する動作

- 利用者の着信音モードと音量を維持してください。消音処理中に利用者が変更した設定も維持します。保存済みの音声状態からの復旧は、過去の保存形式との互換性を保ってください。
- 消音処理の停止時には、音声効果と無音の `AudioTrack` を解放し、音声設定を復元してください。サービスやプロセスの中断後に復旧できる動作も維持してください。
- API の状態と、利用者が確認した音の有無は分けて記録してください。音声効果の有効化、制御権の取得、音声データの書き込みが成功しても、実際にシャッター音が消えたことの証拠にはなりません。
- 診断イベントの履歴件数に上限を設け、以前の検証の非同期処理から届く通知が次の検証に混入しないようにしてください。中断した検証の記録は、後から確認できるように保持してください。
- 記録するアプリのパッケージ名は、関連するカメラアプリに限定してください。無関係な前面アプリのパッケージ名と例外メッセージを診断情報に含めない動作を維持してください。
- 画面の文言を変更するときは、対象となる通常版または検証版のリソースについて、英語・日本語・韓国語でリソース名と書式引数を揃えてください。

## 文書とプルリクエスト

コマンド、ソース構成、ビルドの種類を変更した場合は、開発手順も更新してください。

[CONTRIBUTING.md](CONTRIBUTING.md) と [PR テンプレート](.github/pull_request_template.md) に従ってください。変更内容や規模に制約は設けず、直接 PR を送れます。不具合修正・機種対応の PR には、機種情報、具体的な再現状況、診断情報またはアプリのログの添付が必須です。
