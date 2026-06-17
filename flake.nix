{
  description = "Rootless camera-use audio guard Android app";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config.android_sdk.accept_license = true;
            config.allowUnfree = true;
          };

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [
              "36.0.0"
              "35.0.0"
            ];
            includeEmulator = false;
            includeSystemImages = false;
          };

          androidSdk = androidComposition.androidsdk;
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              androidSdk
              android-tools
              gradle
              jdk17
              ripgrep
            ];

            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
            JAVA_HOME = pkgs.jdk17.home;

            shellHook = ''
              echo "Camera Silencer dev shell"
              echo "  gradle :app:assembleDebug"
              echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
            '';
          };
        }
      );
    };
}
