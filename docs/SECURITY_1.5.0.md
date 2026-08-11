# Security notes — 1.5.0

## Android updater

The Android client contains no GitHub PAT, fine-grained token, GitHub App private key, release keystore or signing password. Public GitHub Releases are queried over HTTPS. Before installation, the APK is accepted only when SHA-256, application package, strictly newer/equal expected versionCode and signing certificate all match the expected release metadata and installed app.

The updater uses Android's normal package installer. Unknown-source authorization, when required, is granted by the user through the per-app Android settings screen.

## Remote Config

The Android app receives only non-secret configuration. `public.app_config` is not readable by the anonymous/authenticated Data API roles; the public GET Edge Function exposes only the allowlisted configuration fields. Administrative writes validate the caller JWT and require the existing `profiles.role = 'admin'` record.

## Startup gate

Protected Activities are not exported. An application-wide Activity lifecycle guard redirects internal Activity launches back through `StartupActivity` whenever the global gate is not `Ready`. Session recovery takes precedence over update blocking so an interrupted session cannot strand an extreme DPI value solely because the app version became blocked.
