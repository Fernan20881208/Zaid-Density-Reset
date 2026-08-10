# Backend rollout state

The Supabase production project already has the `app_config` migration applied and the `app-config` Edge Function deployed. The initial singleton row is configured for Density Reset 1.5.0 (`versionCode` 12) with maintenance disabled, all supported games/profiles enabled, densities 20/72/280, session duration 30 seconds, Quick Tile enabled and GitHub update checks enabled.

The repository includes the matching migration and Edge Function source so source control and deployed backend stay aligned.
