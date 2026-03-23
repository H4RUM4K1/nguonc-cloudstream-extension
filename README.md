# NguonC CloudStream Extension

CloudStream provider module for `phim.nguonc.com` API.

## Build Artifacts

This repo uses GitHub Actions to build plugin artifacts:

- `.cs3`
- `.jar`
- `plugins.json`

Workflow: `.github/workflows/build.yml`

## Test Flow

1. Push changes to `main`.
2. Open GitHub Actions and run the `Build` workflow (or wait for auto-trigger).
3. Download the artifact `nguonc-extension-build`.
4. Import/install the generated plugin on your CloudStream device.

## Module Layout

- `NguoncProvider/build.gradle.kts`
- `NguoncProvider/src/main/kotlin/recloudstream/NguoncPlugin.kt`
- `NguoncProvider/src/main/kotlin/recloudstream/NguoncProvider.kt`

## Note

Use only sources and APIs you are authorized to use.
