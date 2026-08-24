# Matriz QA — Density Reset 1.8.0

| Área | Caso | Resultado esperado |
|---|---|---|
| Actualización | PR contra `main` | lint, unit tests, debug y release minificada completan |
| Publicación | merge a `main` | APK release firmado, certificado verificado, tag y `update.json` |
| Compatibilidad | Android 8–9 | sesión/DPI funcionan; grabación pide permiso de almacenamiento |
| Compatibilidad | Android 10–13 | grabación usa MediaStore y audio interno solo si es compatible |
| Compatibilidad | Android 14–16 | consentimiento MediaProjection nuevo en cada sesión y FGS tipado |
| Quick Settings | tiles `FF` y `FFM` | abren licencia/actualización si corresponde y luego el juego correcto |
| Perfil rápido | default definido/no definido | usa default; en su ausencia usa último perfil o primer perfil habilitado |
| Isla | panel de notificaciones parcialmente abierto | muestra FF/FFM, DPI, batería y datos mínimos; no crea overlay |
| HUD | overlay activado sin permiso | se solicita permiso; si se deniega, monitores continúan sin HUD |
| DND | Prioridad activado | solicita acceso especial, aplica Prioridad y restaura filtro anterior |
| Brillo | valor 10–100 % | solicita WRITE_SETTINGS, aplica y restaura modo/valor anterior |
| Rotación | bloqueo activado | desactiva autorrotación durante la sesión y restaura ambos valores |
| Volumen | valor 10–100 % | aplica contra el máximo real del stream y restaura el volumen anterior |
| Grabación | consentimiento aceptado | crea MP4 H.264 y muestra STOP en la isla |
| Grabación | audio interno rechazado/no compatible | graba video sin micrófono y sin bloquear el juego |
| Grabación | consentimiento cancelado | abre el juego sin grabar |
| Playtime | salida confirmada/manual/error | registra una sola vez por sessionId y desglosa por DPI |
| Booster | modos existentes | Juego, Ahorro, Máximo y Ultra máximo siguen disponibles |
| Booster | nuevos modos | Equilibrado y Ultra ahorro quedan persistidos por juego |
| Térmico | HOT en modo rendimiento | baja a Equilibrado y lo informa |
| Térmico | VERY_HOT con Battery disponible | baja a Ultra ahorro y desactiva muestreo FPS costoso |
| Restauración | proceso/servicio reiniciado | recupera snapshots persistidos y no deja cambios temporales olvidados |
| Restauración | salida antes de 20 s | restaura Booster/automatizaciones; DPI espera su deadline original |
| Restauración | salida después de 20 s | DPI ya restaurado; termina seguimiento y restaura lo restante |

Prueba física prioritaria: POCO `duchamp`, HyperOS 3, Android 16/SDK 36, Shizuku autorizado.
