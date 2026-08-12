# Density Reset 1.6.0

## Game Booster automático

- Se integra Game Booster directamente en el Game Launcher existente para Free Fire y Free Fire MAX.
- Cada juego guarda de forma independiente uno de tres modos: Modo Juego, Ahorro de batería o Máximo rendimiento.
- La aplicación detecta primero el dispositivo, ROM y capacidades reales antes de intentar una optimización.
- Game Mode se modifica únicamente cuando Android permite conocer el modo actual y los modos disponibles, de modo que el cambio pueda restaurarse con seguridad.
- Modo Juego utiliza `standard`, Ahorro de batería utiliza `battery` y Máximo rendimiento utiliza `performance` únicamente cuando el GameManager del dispositivo declara ese modo como disponible.
- No se utiliza Fixed Performance Mode, no se desactiva thermal throttling, no se cambian governors, no se escribe a sysfs y no se modifican archivos ni memoria del juego.

## Perfiles por ROM

- Detección automática de HyperOS, MIUI, One UI, ColorOS, Realme UI, AOSP y ROM desconocida.
- Adaptadores Xiaomi, Samsung, Oplus y AOSP con decisiones basadas en capacidades detectadas en tiempo de ejecución.
- Los servicios de juego OEM se detectan solo como capacidad/diagnóstico; no se ejecutan comandos OEM no verificados.
- Si una ROM Xiaomi no puede distinguirse con señales fiables, la interfaz muestra Xiaomi sin inventar una versión de HyperOS o MIUI.
- Remote Config puede habilitar o deshabilitar comportamientos ya compilados, pero nunca entrega comandos shell a la aplicación.

## Monitores

- RAM disponible y total mediante `ActivityManager.MemoryInfo`, con estados calculados proporcionalmente a la memoria total.
- Batería real con porcentaje, carga/descarga y consumo desde el inicio de la sesión.
- Estado térmico mediante las APIs estándar de Android. Una temperatura numérica solo se muestra cuando existe una lectura real de batería y siempre se identifica su fuente.
- FPS mediante `dumpsys gfxinfo PACKAGE framestats` y timestamps recientes de frames.
- Los Hz de la pantalla nunca se utilizan como FPS. Cuando no existe una muestra fiable se muestra `No disponible`.
- Los monitores usan intervalos moderados y sus fallos no impiden abrir el juego.

## Sesión y restauración

- Se reutiliza el mismo foreground service de la sesión de juego para DPI, Game Booster, monitores y restauración.
- Se conserva el comportamiento de 1.5.3: el DPI seleccionado se verifica y exactamente 20 segundos después se ejecuta `wm density reset` y se verifica el resultado.
- El Game Booster puede continuar después de esos 20 segundos mientras el juego siga abierto.
- Al terminar la partida se restaura el Game Mode anterior guardado en snapshot, se detienen los monitores y se limpia la sesión.
- Una restauración pendiente conserva su ruta de recuperación incluso si Remote Config cambia durante la partida o si el proceso necesita recuperarse.
- El gesto de ambos botones de volumen continúa disponible como restauración de emergencia.

## Interfaz

- Nueva tarjeta Liquid Glass de Game Booster dentro de cada juego.
- Nueva tarjeta Estado del juego con FPS, RAM, batería y temperatura usando texto además del color.
- Detalle explicativo al tocar cada métrica.
- Diagnóstico de fabricante/ROM, perfil de adaptador y capacidades compatibles.
- Opción avanzada `Volver a detectar dispositivo`.
