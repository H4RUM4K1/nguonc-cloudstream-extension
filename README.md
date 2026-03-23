# CloudStream GitHub Extension Template (Legal API)

This repository is a starter template for a **CloudStream provider extension** that uses an **official API** you are allowed to use.

## What this template includes

- A provider skeleton (`LegalApiProvider.kt`) with:
  - `search(query)`
  - `load(url)`
  - `loadLinks(data)`
- A plugin entrypoint (`LegalApiPlugin.kt`) for registration
- A GitHub workflow (`.github/workflows/release.yml`) to build and attach artifacts on tag release

## Important

Only integrate APIs/sources you are licensed or authorized to use.

## Repository layout

- `src/main/kotlin/com/example/legalapi/LegalApiProvider.kt`
- `src/main/kotlin/com/example/legalapi/LegalApiPlugin.kt`
- `.github/workflows/release.yml`

## How to adapt to your API

1. Open `LegalApiProvider.kt`.
2. Replace `mainUrl` and `apiBase` with your API domain.
3. Update endpoint paths in:
   - `search(query)`
   - `load(url)`
   - `loadLinks(data)`
4. Map JSON fields into CloudStream response models.
5. Add required request headers (`Referer`, `Origin`, `User-Agent`, `Authorization`) if your API requires them.

## Suggested GitHub steps

1. Create a new GitHub repository.
2. Push this folder:
   - `git init`
   - `git add .`
   - `git commit -m "Initial CloudStream extension template"`
   - `git branch -M main`
   - `git remote add origin <your-repo-url>`
   - `git push -u origin main`
3. Create a release tag (example `v0.1.0`) to trigger the workflow.

## Notes on CloudStream dependency/setup

CloudStream plugin dependencies and Gradle setup can vary by template/version. If you want, I can next generate a full buildable Gradle setup matching the latest template you prefer.
