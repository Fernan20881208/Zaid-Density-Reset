# Density Reset 1.5.0 architecture

The new feature set is additive. Existing XML/ViewBinding controls and the Shizuku density/session implementation remain available.

- `launcher/` owns Game Launcher presentation, installed-app metadata and per-game profile preferences. It delegates play/restore to the existing game session controller.
- `remoteconfig/` owns validated runtime configuration and its local DataStore cache. UI code never accesses Supabase directly.
- `quicktile/` projects persisted density/session/startup state into `TileService`; restore actions delegate to existing controllers/services.
- `update/` owns GitHub Release discovery, download, APK verification and standard Android installation.
- `startup/` is the global gate shared by launcher, legacy controls, license flow, Tile and secondary Activities.

Startup priority is:

1. Recover an incomplete/expired DPI session.
2. Enforce a newer/blocked version.
3. Enforce maintenance mode.
4. Validate license.
5. Open the application.
