# Camera Silencer

Android 端末でカメラ利用中にカメラアプリのシャッター音を自動で抑制します。

カメラを開いた時だけ働き、カメラを閉じると自動的に音声設定が復元されます。余計な操作は不要です。

## 主な特徴

- **自動で動作**: カメラの使用開始時に自動で有効化、終了時に自動で無効化
- **シンプル**: 複雑な設定不要。アプリを開いて「Start guard」を押すだけ
- **安全**: カメラ権限不要。インターネット接続なし
- **復旧可能**: 音量がおかしくなった場合は、アプリから「Restore audio now」ボタンで復元

## 対応機器

- Android 8.0 以上でインストール可能
- Android 12 以上推奨
- **Pixel 10a** で実機検証済み
- 効果は端末メーカー、地域設定、OS ビルド、カメラアプリの実装に依存します

## ビルド

この repo のルートディレクトリを正の作業ディレクトリとして扱います。

```sh
nix develop --command gradle :app:assembleDebug
```

生成される debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

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

### 有効化

1. **Camera Silencer** を開く
2. **「Start guard」** をタップ
3. ステータスに **「Watching camera use」** と表示されたら準備完了
4. カメラアプリを開く → シャッター音が消えます

### 無効化

- **「Stop guard」** をタップして手動で止める
- アプリを終了しても、Foreground 通知には効果が残ります

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
- A: Foreground service と無音 AudioTrack を使うため、常駐分の消費はあります。処理はカメラ使用中だけ強くなります。

**Q: 特別な権限は必要ですか？**
- A: 不要です。カメラ権限やアクセシビリティ権限は使いません。

## トラブルシューティング

### シャッター音が消えない

- 端末やカメラアプリの音声実装により、通常権限だけでは抑制できない場合があります
- 端末を再起動してから再度試してください
- アプリログで詳細を確認：
  ```sh
  nix develop --command adb logcat -v time -s CameraGuardService AudioSilencer OutputMixGuard
  ```

### 音量が戻らない

- アプリを開いて **「Restore audio now」** をタップ
- または端末を再起動してください
- 復旧できない場合は設定 > 音量 から手動調整してください

## 技術詳細

このアプリは、Android の公開オーディオ API のみを使用して、カメラ使用中だけ出力側を制御します：

- `CameraManager.AvailabilityCallback` でカメラの使用状態を検知
- `AudioManager` API で音声モード・ストリーム音量を一時変更
- `AudioEffect` (Equalizer, DynamicsProcessing) で出力を減衰
- Foreground Service でカメラ解放時に自動復旧

詳細は [ソースコード](app/src/main/java/dev/serika/camerasilencer/) をご覧ください。

## ライセンス

MIT License

## 注意

- 端末の地域設定や OS ビルドにより動作が異なる場合があります
- サードパーティ製カメラアプリには非対応の場合があります
