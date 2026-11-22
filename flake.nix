{
  description = "TFC tracker (Android): build shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f system);
    in
    {
      devShells = forAll (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37.0" ];
            # 36.0.0 is AGP 9.5's default; 37.0.0 for aapt2 on Linux.
            buildToolsVersions = [
              "36.0.0"
              "37.0.0"
            ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk21_headless
              android.platform-tools
              # tools/install-autoboot.sh patches the phones' system image offline.
              pkgs.e2fsprogs
            ];
            JAVA_HOME = pkgs.jdk21_headless.home;
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            # AGP's own aapt2 is a generic Linux binary; use the SDK's.
            GRADLE_OPTS = pkgs.lib.optionalString pkgs.stdenv.hostPlatform.isLinux "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/37.0.0/aapt2";
          };
        }
      );
    };
}
