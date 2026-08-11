# Device acceptance checklist — 1.5.0

Use a real Android device with Shizuku authorized before marking hardware validation complete.

- Verify Free Fire and Free Fire MAX real icons/labels and installed states.
- Verify each game's last/default profile persists independently.
- Start sessions with Ultra/High/Low and confirm the existing restart/restore flow.
- Toggle each remote game/profile switch and verify disabled items remain visible.
- Change valid remote density/session duration and verify new sessions use it.
- Verify maintenance and announcement presentation.
- Add Quick Settings Tile; test normal, override, active session, disabled and mandatory-update states.
- Publish a signed test Release with a higher versionCode and verify mandatory blocking/download/install.
- Exercise corrupted hash, wrong package, wrong version and wrong signing certificate fixtures.
- Cancel the installer and verify the app remains blocked.
- Interrupt a DPI session/reboot into an update-required state and verify restoration occurs before the update gate.
