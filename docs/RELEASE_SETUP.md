# Signed Release setup

The `Publicar Density Reset` workflow is triggered only by version tags (`v*`). It refuses to publish when signing secrets are missing or when the tag does not exactly match `versionName`.

Configure these GitHub Actions Secrets before creating a release tag:

- `ANDROID_KEYSTORE_BASE64`: base64 encoding of the existing Density Reset release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore itself must not be committed.

## Release sequence

1. Set a strictly higher Android `versionCode` and the desired `versionName`.
2. Ensure `public.app_config.latest_version_code` and `min_supported_version_code` reflect the desired policy only when the matching signed Release will be available.
3. Push/merge the tested code.
4. Create and push tag `v<versionName>`.
5. GitHub Actions builds the signed APK, verifies its certificate, calculates SHA-256 and generates `update.json`.
6. The workflow creates a draft Release containing both the APK and `update.json`.
7. It verifies both assets exist, then publishes the draft as the latest stable Release.

If a step after draft creation fails, the workflow removes the incomplete draft Release. The Android updater never contains a GitHub PAT or private signing material.
