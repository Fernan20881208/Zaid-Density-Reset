# Density Reset 1.8.0

## Game Launcher

- Nueva notificación compacta tipo isla en el panel de Android con juego, DPI, batería,
  temperatura, tiempo de sesión y controles mínimos.
- Tiles rápidos independientes `FF` y `FFM`, además de atajos dinámicos para iniciar cada juego
  con su perfil DPI predeterminado.
- Color de acento obtenido del icono instalado de cada juego.
- Tiempo total de juego y desglose por perfil DPI.
- Matriz ampliada de capacidades reales: Game Mode actual/disponible, monitores, permisos,
  grabación, tiles y atajos.

## Booster y automatizaciones

- Se conservan todos los modos existentes y se agregan `Equilibrado` y `Ultra ahorro de batería`.
- Protección térmica adaptativa: reduce modos exigentes sin desactivar las protecciones de Android.
- Ajustes opcionales por juego para DND Prioridad, brillo, rotación y volumen multimedia.
- Los valores anteriores se guardan y restauran al terminar o recuperar la sesión.

## Grabación

- Grabación opcional con el consentimiento MediaProjection de Android en cada sesión.
- Video H.264 y audio interno cuando el dispositivo y el juego permiten capturarlo.
- El micrófono permanece apagado; si el audio interno no está disponible, la sesión continúa en
  video solamente.
- Archivos locales en `Movies/Density Reset`, con acción para detener la grabación desde la isla.

## Estabilidad

- `versionCode 19`, compilación release minificada validada en CI y publicación estable firmada
  mediante el flujo existente de GitHub OIDC + Supabase Vault.
- Restauración coordinada de DPI, Game Mode, DND, brillo, rotación, volumen, HUD y grabación ante
  salida normal, restauración manual, error o recuperación de proceso.
