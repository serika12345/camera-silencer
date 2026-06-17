# Pixel Camera Silencer

Pixel 10a で、純正カメラ利用中だけ最小限の `AudioManager` 操作を試す実験アプリです。

このリポジトリはローカル開発用です。リモートは設定していません。

## 方針

- `CameraManager.AvailabilityCallback` でカメラ使用中を検知する
- カメラ使用中だけ音声状態を退避し、最小限の一時変更を行う
- カメラ解放後に退避状態へ戻す
- `CAMERA`, `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, Accessibility, `INTERNET` は使わない

## 実装

- Foreground service が `CameraManager.AvailabilityCallback` を登録する
- いずれかの camera ID が unavailable になったらガードON
- すべて available に戻ったらガードOFF
- ガードON時:
  - 現在の `AudioManager.mode` と `STREAM_SYSTEM` / `STREAM_MUSIC` の音量・mute状態を保存
  - `STREAM_SYSTEM_ENFORCED` 相当のストリームも保存
  - `AudioManager.setMode(MODE_IN_COMMUNICATION)`
  - `STREAM_SYSTEM` / `STREAM_MUSIC` / `STREAM_SYSTEM_ENFORCED` 相当を一時的に mute + volume 0
  - output mix/session `0` に `Equalizer` と `DynamicsProcessing` を挿して強く減衰
  - volume 0 の無音 `AudioTrack` を再生して出力経路を維持
- ガードOFF時:
  - 保存した音量・mute状態・mode を復元
- 念のため、復元用スナップショットは `SharedPreferences` にも保存する

## 権限

- `MODIFY_AUDIO_SETTINGS`: 一時的な音声モード/ストリーム音量操作
- `FOREGROUND_SERVICE`: 監視を明示する通知付きサービス
- `FOREGROUND_SERVICE_SPECIAL_USE`: Android 14+ の foreground service type 要件
- `POST_NOTIFICATIONS`: Android 13+ の通知表示

## ビルド

```sh
nix develop
gradle :app:assembleDebug
```

## インストール

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

起動後、通知権限を許可して `Start guard` を押してください。その後、Pixel の純正カメラを開いて動作を確認します。

## 実機で見るログ

```sh
adb logcat -s CameraGuardService AudioSilencer
```

音量が戻らない場合は、アプリを開いて `Restore audio now` を押してください。ミュートON時のスナップショットは端末内の `SharedPreferences` にも保存しています。

## 注意

純正カメラのシャッター音を通常権限で直接OFFにするAPIはありません。このアプリは、カメラ利用中だけ公開オーディオAPIで出力側を抑制する実験実装です。端末・地域・OSビルドにより効き方が変わります。
