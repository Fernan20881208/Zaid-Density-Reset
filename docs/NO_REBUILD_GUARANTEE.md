# Additive integration scope

No existing Density Reset feature is intentionally removed or reimplemented from scratch in this branch. The legacy XML/ViewBinding screen remains accessible, the existing `ShizukuDensityController` remains the density authority, and `DpiGameSessionService` remains responsible for game-session snapshot/apply/verification/restoration. New launcher/tile code delegates to those components.
