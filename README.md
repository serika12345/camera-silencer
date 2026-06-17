# Camera Silencer

Android 端末でカメラ利用中にカメラアプリのシャッター音を自動で抑制します。

カメラを開いた時だけ働き、カメラを閉じると自動的に音声設定が復元されます。余計な操作は不要です。

## 主な特徴

- **自動で動作**: カメラの使用開始時に自動で有効化、終了時に自動で無効化
- **起動時に再アーム**: Always / Follow silent のモードでは再起動後に再有効化通知を表示
- **動作モード選択**: 端末の消音/バイブ連動、カメラ中は常時消音、手動制御を選択可能
- **シンプル**: 手動モードではアプリを開いて手動ガードのトグルを押すだけ
- **安全**: カメラ権限不要。インターネット接続なし
- **復旧可能**: 音量がおかしくなった場合は、アプリから「Restore audio now」ボタンで復元

## 対応機器

- Android 8.0 以上でインストール可能
- Android 12 以上推奨
- **Pixel 10a** で実機検証済み
- 効果は端末メーカー、地域設定、OS ビルド、カメラアプリの実装に依存します

## ビルド

このリポジトリ直下にcdして下記を実行してください。

```sh
nix develop --command gradle :app:assembleDebug
```

生成される debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

F-Droid 系の配布・検証向け release APK:

```sh
nix develop --command gradle :app:assembleRelease
```

署名鍵を指定しない場合、生成される APK は unsigned です。F-Droid 本家のようにリポジトリ側でビルドして署名する配布形態では、この状態が前提になります。

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

## GitHub Actions

`main` への push と pull request で release APK をビルドし、workflow artifact として保存します。

`v*` タグを push すると、同じ release APK と SHA-256 ファイルを GitHub Releases に配置します。

```sh
git tag v0.1.2
git push origin v0.1.2
```

Actions 画面から `Android` workflow を手動実行し、`release_tag` に `v0.1.2` のようなタグを指定して Release を作成することもできます。`release_tag` を空にした場合はビルドのみ実行します。

GitHub Releases に直接インストール可能な signed APK を置きたい場合は、リポジトリ secrets に以下を設定してください。未設定の場合は F-Droid 系の署名処理向けに `release-unsigned` APK を配置します。

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

## F-Droid 系配布

このリポジトリは F-Droid 系の配布を想定して、以下を含めています。

- `assembleRelease` で unsigned release APK を生成
- F-Droid/IzzyOnDroid 系が参照できる Fastlane metadata: `fastlane/metadata/android/`
- fdroiddata へ提出するための下書き metadata: `fdroid/metadata/dev.serika.camerasilencer.yml`
- Play Services、Firebase、広告、解析 SDK、外部通信なし

F-Droid 本家へ提出する場合は、`fdroid/metadata/dev.serika.camerasilencer.yml` を fdroiddata 側の `metadata/dev.serika.camerasilencer.yml` として調整し、タグ `v0.1.2` 以降をビルド対象にしてください。

## インストール

```sh
nix develop --command adb install -r app/build/outputs/apk/debug/app-debug.apk
nix develop --command adb shell am start -n dev.serika.camerasilencer/.MainActivity --ez start_guard true
```

Android 13 以上で通知権限を手動付与する場合:

```sh
nix develop --command adb shell pm grant dev.serika.camerasilencer android.permission.POST_NOTIFICATIONS
```

その後、カメラアプリを開いて動作確認します。

## 使い方

### モード

- **Follow silent/vibrate mode**: 端末が消音またはバイブのときだけ、カメラ使用中に音声を抑制します。再起動後は通知をタップするか、アプリを開くと再有効化します。
- **Always silence camera**: 端末の音量状態に関係なく、カメラ使用中は音声を抑制します。再起動後は通知をタップするか、アプリを開くと再有効化します。
- **Manual control**: 手動ガードのトグルをオンにしたときだけ監視します。再起動後の通知は出しません。

### 手動モードの有効化

1. **Camera Silencer** を開く
2. **Manual control** を選ぶ
3. 手動ガードのトグルをタップして **「Start manual guard」** から **「Stop manual guard」** に切り替える
4. ステータスに **「Watching camera use」** と表示されたら準備完了
5. カメラアプリを開く → シャッター音が消えます

### 無効化

- Manual control では手動ガードのトグルを **「Stop manual guard」** から **「Start manual guard」** に戻すと監視を止めます
- Always / Follow silent のモードでは、アプリを終了しても Foreground 通知と監視は残ります
- 端末再起動後は、通知をタップするかアプリを開いて再アームしてください

### 音量がおかしくなった

1. アプリを開く
2. **「Restore audio now」** をタップ
3. 音量設定が元に戻ります

## よくある質問

**Q: どのカメラアプリに対応していますか？**
- A: Android のカメラ使用状態を見て出力側を抑制するため、特定のカメラ package には固定していません。Pixel 10a の Google Camera では確認済みですが、他の端末やカメラアプリでは効き方が変わります。

**Q: 他のアプリに影響しますか？**
- A: カメラ使用中だけ音声設定を一時的に変更し、終了時に元に戻します。カメラ中に他の音声再生もある場合は、その間だけ影響する可能性があります。

**Q: インターネット接続は必要ですか？**
- A: 不要です。完全にオフライン動作します。

**Q: 電池消費は？**
- A: Foreground service の常駐分の消費はあります。無音 AudioTrack と音声減衰処理はカメラ使用中だけ動きます。

**Q: 特別な権限は必要ですか？**
- A: カメラ権限、アクセシビリティ権限、使用状況アクセス、オーバーレイ、DND/通知ポリシーアクセスは使いません。

## トラブルシューティング

### シャッター音が消えない

- 端末やカメラアプリの音声実装により、通常権限だけでは抑制できない場合があります
- 端末再起動後にカメラを先に起動した場合は、Camera Silencer を起動してからカメラアプリを強制停止し、もう一度カメラを開いてください
- アプリ内の **「Open camera app info」** からカメラアプリのアプリ情報を開き、**「強制停止」** を実行できます
- Pixel の Google Camera で一度効かない状態になった場合も、Google Camera を強制停止してから、Camera Silencer を起動済みの状態でカメラを開き直してください
- 端末を再起動してから再度試してください
- アプリログで詳細を確認：
  ```sh
  nix develop --command adb logcat -v time -s BootReceiver CameraGuardService AudioSilencer OutputMixGuard
  ```

### 音量が戻らない

- アプリを開いて **「Restore audio now」** をタップ
- または端末を再起動してください
- 復旧できない場合は設定 > 音量 から手動調整してください

## 技術詳細

このアプリは、Android の公開オーディオ API のみを使用して、カメラ使用中だけ出力側を制御します：

- `CameraManager.AvailabilityCallback` でカメラの使用状態を検知
- `RECEIVE_BOOT_COMPLETED` で再起動後に auto 系モードの再アーム通知を表示
- `AudioManager.getRingerMode()` と `RINGER_MODE_CHANGED_ACTION` で消音/バイブ連動
- `AudioManager` API で音声モード・ストリーム音量を一時変更
- `AudioEffect` (Equalizer, DynamicsProcessing) で出力を減衰
- カメラ使用中だけ音量 0 の AudioTrack を再生し、出力経路を維持
- Foreground Service でカメラ解放時に自動復旧

詳細は [ソースコード](app/src/main/java/dev/serika/camerasilencer/) をご覧ください。

## ライセンス

MIT License

## 注意

- 端末の地域設定や OS ビルドにより動作が異なる場合があります
- サードパーティ製カメラアプリには非対応の場合があります
