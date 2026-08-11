# Remote Config

Density Reset reads public, non-secret runtime configuration from the Supabase Edge Function `app-config`. The Android client never receives `service_role`, GitHub tokens, or signing secrets.

## Configuration fields

The single `public.app_config` row controls:

- maintenance mode/message
- minimum/latest supported version code, force update and blocked version codes
- Free Fire / Free Fire MAX availability
- Sensi Ultra / Alta / Baja availability and densities
- game session duration
- in-app announcement
- Quick Settings Tile availability
- GitHub update checks

Android validates all remote values before use. Density fallback values remain 20 / 72 / 280 and session duration fallback remains 30 seconds.

## Reading configuration

`GET /functions/v1/app-config` is intentionally public and returns only the non-secret configuration object. Direct Data API access to `public.app_config` is not granted to `anon` or `authenticated`; the Edge Function reads through its server-side service role.

## Updating configuration

`PUT /functions/v1/app-config` requires a valid Supabase user JWT and `public.profiles.role = 'admin'`. Only the allowlisted fields are accepted and every numeric/text value is bounded before the database update.

Example request body:

```json
{
  "maintenance_mode": false,
  "latest_version_code": 12,
  "min_supported_version_code": 12,
  "force_update": false,
  "sensi_ultra_density": 20,
  "sensi_high_density": 72,
  "sensi_low_density": 280,
  "game_session_duration_seconds": 30,
  "blocked_version_codes": []
}
```

Do not expose the server-side `SUPABASE_SERVICE_ROLE_KEY` in Android or the web admin client.
