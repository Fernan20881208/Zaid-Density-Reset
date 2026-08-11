# Density Reset 1.5.0 — QA matrix

This matrix distinguishes automated/static validation from Android device validation. Device-only rows must be executed on a real device with Shizuku and the target games installed where applicable.

| Scenario | Expected result | Validation |
|---|---|---|
| Game Launcher opens after startup gate | Launcher is the first application surface after update/maintenance/license checks | CI + device |
| Free Fire installed | Real label/icon/package shown, `● Instalado`, JUGAR enabled when Shizuku is ready | Device |
| Free Fire not installed | Fallback label/package shown, `○ No instalado`, JUGAR disabled | Device |
| Free Fire MAX installed | Real label/icon/package shown, `● Instalado` | Device |
| Profile selection | Ultra/High/Low update selected state without applying DPI until JUGAR | Unit/static + device |
| Per-game last profile | Playing each game saves its own last profile | Device |
| Per-game default profile | Default survives restart independently per package | Device |
| Disabled game remotely | Game remains visible and reports temporary unavailability | Backend + device |
| Disabled DPI profile remotely | Profile remains visible and reports temporary unavailability | Backend + device |
| Remote density values | Valid values replace fallback; invalid values revert to 20/72/280 | Unit test |
| Remote session duration | New sessions use validated 5–150 second duration and persist restoreAt | Unit/static + device |
| Remote announcement | Liquid Glass announcement card appears only when enabled | Device |
| Remote Config available | Live config replaces cache and persists locally | Backend + device |
| Remote Config unavailable | Cached noncritical config can render; security startup gate follows verified fallback rules | Device/network fault |
| Maintenance mode | No launcher; only maintenance screen and retry | Device/backend |
| Quick Tile normal DPI | Inactive, `DPI original`; tap opens startup gate/Game Launcher | Device |
| Quick Tile modified DPI | Active with density subtitle; tap uses existing density reset controller | Device |
| Quick Tile during session | Active `FF/FF MAX · N DPI`; tap prioritizes existing session restore | Device |
| Quick Tile remotely disabled | `STATE_UNAVAILABLE`, no DPI action | Device/backend |
| Quick Tile with mandatory update | No DPI/game action; opens mandatory update gate | Device |
| Latest version installed | Startup continues when GitHub/remote policy confirms current version | Integration/device |
| New stable GitHub Release | current version blocks when release versionCode is higher | Integration/device |
| Draft/prerelease | Not accepted as required stable update | Static/integration |
| GitHub 403/404/429/5xx/timeout | Uses verified remote fallback where sufficient, otherwise blocks with retry | Network fault |
| GitHub unavailable + min supported higher | App remains blocked | Unit/static + network fault |
| Blocked version code | App remains blocked and routes to update screen | Unit/static + device |
| Download interrupted | DownloadManager state reports interruption/failure and allows retry | Device/network fault |
| Existing completed download | Same release APK is reused instead of downloading again | Device |
| APK SHA-256 mismatch | APK deleted; install is not offered | Unit/static + device fixture |
| APK package mismatch | APK deleted; install is not offered | Device fixture |
| APK version mismatch/not newer | APK deleted; install is not offered | Device fixture |
| APK signer mismatch | APK deleted; install is not offered | Device fixture |
| Unknown-app install permission off | Opens Android per-app unknown source settings; gate remains blocked | Device |
| Installer cancelled | Returns to mandatory update screen; no app access | Device |
| Reopen after successful update | New BuildConfig version satisfies gate and old temp APK is cleaned | Device |
| Back button on mandatory update | Does nothing; no bypass | Device |
| Internal/secondary Activity during block | Application lifecycle guard redirects to startup/update gate | Device |
| Session notification during block | Notification opens StartupActivity, not legacy controls | Static + device |
| DPI session incomplete + mandatory update | Existing DpiGameSessionService recovery runs before update gate | Device fault/restart |
| Release workflow | Signed APK + exact SHA-256 update.json uploaded to draft; release published only after both assets exist | GitHub Actions |

## Automated commands

CI must pass:

```bash
./gradlew clean lintDebug testDebugUnitTest assembleDebug
```

Release publishing additionally runs:

```bash
./gradlew clean lintDebug testDebugUnitTest assembleRelease
```

and verifies the signed APK with Android `apksigner`, computes SHA-256, generates `update.json`, verifies both assets, and only then publishes the draft Release.
