# F-Droid Submission

This repository is prepared for F-Droid-style distribution.

## Current App Metadata

- Application ID: `dev.serika.camerasilencer`
- Current version: `0.1.3`
- Current version code: `3`
- Source commit: `96a6961bd07314679a9fcaff72e771548d4d1778`
- F-Droid build subdir: `app`
- Metadata draft: `fdroid/metadata/dev.serika.camerasilencer.yml`
- Fastlane metadata: `fastlane/metadata/android/`

## Verification Performed

From this repository:

```sh
nix develop --command gradle :app:assembleRelease
nix develop --command gradle :app:assembleDebug
nix shell nixpkgs#actionlint --command actionlint .github/workflows/android.yml
```

In a local sparse checkout of `fdroid/fdroiddata`, with this repository's
metadata copied to `metadata/dev.serika.camerasilencer.yml`:

```sh
fdroid lint dev.serika.camerasilencer
fdroid rewritemeta dev.serika.camerasilencer
```

`fdroid lint` and `fdroid rewritemeta` passed. A local `fdroid build -l`
attempt reached the Gradle wrapper step, but the local macOS/Nix fdroidserver
environment stalled in `gradlew-fdroid clean`; the normal Gradle release build
and GitHub Actions release build both pass.

## Submit To F-Droid

F-Droid's preferred path for a new app is a merge request to `fdroid/fdroiddata`.
Use a GitLab account with a fork of `https://gitlab.com/fdroid/fdroiddata`.

```sh
git clone git@gitlab.com:<your-gitlab-user>/fdroiddata.git
cd fdroiddata
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream
git checkout -b dev.serika.camerasilencer upstream/master
cp /path/to/camera-silencer/fdroid/metadata/dev.serika.camerasilencer.yml metadata/dev.serika.camerasilencer.yml
fdroid lint dev.serika.camerasilencer
fdroid rewritemeta dev.serika.camerasilencer
git add metadata/dev.serika.camerasilencer.yml
git commit -m "Add Camera Silencer"
git push origin dev.serika.camerasilencer
```

Then open a merge request:

- Source: `<your-gitlab-user>/fdroiddata:dev.serika.camerasilencer`
- Target: `fdroid/fdroiddata:master`

## Merge Request Description

Use GitLab's `App Inclusion` description template.

In the merge request editor:

1. Choose the `App Inclusion` template.
2. Read the template instructions and check the task boxes after verifying them.
3. Do not add separate summary or description text in the merge request body.
   F-Droid should pull store listing text from upstream
   `fastlane/metadata/android/`.
4. Confirm the metadata uses the full commit hash
   `96a6961bd07314679a9fcaff72e771548d4d1778`, not a tag or branch.
5. Confirm `subdir: app` is set in the build block and `output` is absent.
