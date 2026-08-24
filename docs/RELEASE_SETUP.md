# Publicación estable firmada

`Publicar Density Reset` se ejecuta al hacer push a `main` o manualmente. No publica APKs de debug.
La firma persistente se obtiene durante GitHub Actions desde la función privada
`release-signing`, autenticando el workflow con GitHub OIDC (`audience=density-reset-release`). El
keystore y sus contraseñas no se guardan en el repositorio ni como artefactos.

## Secuencia actual

1. Incrementar `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Abrir un PR y esperar que `Compilar APK` complete lint, pruebas, debug y una compilación release
   minificada sin firma.
3. Fusionar el PR probado en `main`.
4. El workflow obtiene la firma mediante OIDC, ejecuta nuevamente pruebas y `assembleRelease`.
5. Verifica que el certificado del APK coincide con el SHA-256 esperado por el servicio de firma.
6. Calcula el SHA-256 del APK y crea `update.json` con `mandatory=true` y el `versionCode` actual.
7. Crea `v<versionName>`, sube APK + metadata a un draft, verifica ambos assets y publica la
   Release como estable.

Si la Release ya existe, el workflow termina sin reemplazarla. Si falla después de crear el draft,
el draft incompleto se elimina. `public.app_config.latest_version_code` y
`min_supported_version_code` solo deben cambiarse cuando la Release firmada correspondiente ya
esté disponible; ese cambio no es necesario para compilar ni revisar el PR.
