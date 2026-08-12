# Density Reset 1.6.1

## Monitor flotante dentro del juego

- FPS, RAM, batería y temperatura ahora se muestran en un HUD flotante sobre Free Fire o Free Fire MAX durante la sesión.
- El HUD usa el permiso oficial de Android `Mostrar sobre otras apps` (`SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`).
- La aplicación solicita al usuario habilitar ese permiso antes de iniciar una sesión cuando los monitores están activos.
- El overlay solo presenta datos reales producidos por los monitores existentes; no sustituye FPS por Hz ni inventa temperaturas.
- Si el permiso no está concedido o Android rechaza la ventana, los monitores flotantes no se consideran activos.
- El overlay desaparece al restaurar o finalizar la sesión.

## Ultra máximo rendimiento · Modo benchmark

- Se añade un cuarto modo de Game Booster: `Ultra máximo rendimiento`.
- El modo intenta usar Game Mode `performance` cuando el juego y el dispositivo lo soportan.
- Además intenta activar el Fixed Performance Mode oficial de Android mediante `cmd power set-fixed-performance-mode-enabled true` únicamente cuando la capacidad está disponible y el comando es aceptado por el sistema.
- Fixed Performance Mode está orientado a pruebas de rendimiento repetibles; no se presenta como una garantía de clocks máximos.
- Si Fixed Performance no puede activarse después de cambiar Game Mode, la aplicación intenta revertir inmediatamente Game Mode para evitar dejar un modo Ultra parcial.
- Al terminar la sesión, primero se desactiva Fixed Performance Mode y después se restaura el Game Mode anterior guardado.
- El estado de restauración se persiste para poder recuperarlo si el servicio o proceso se reinicia.

## Seguridad y compatibilidad

- No se desactiva thermal throttling.
- No se modifican governors, sysfs ni archivos del juego.
- No se utilizan comandos OEM secretos o no verificados.
- El monitor térmico continúa activo también durante el modo benchmark.
- Se conserva el flujo verificado de sensibilidad: el DPI temporal mantiene exactamente su ventana de 20 segundos y después ejecuta `wm density reset` con verificación.

## Versión

- versionName: `1.6.1`
- versionCode: `17`
