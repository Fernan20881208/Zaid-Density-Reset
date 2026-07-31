# Density Reset

Aplicación Android en Kotlin para controlar la densidad lógica del sistema mediante Shizuku, sin root. El paquete publicado es `com.zaidnavarro.ds` y la interfaz conserva el diseño Liquid Glass del proyecto.

## Funciones principales

- DPI múltiple:
  - **Sensi Ultra — 20 DPI:** escala extrema.
  - **Sensi Alta — 72 DPI:** escala competitiva.
  - **Sensi Baja — 280 DPI:** escala equilibrada.
- Lectura del DPI físico, DPI efectivo y estado real del override.
- Aplicación directa mediante Binder de WindowManager con identidad `shell` de Shizuku.
- Restablecimiento visible y gesto global con **Volumen arriba + Volumen abajo durante 2 segundos**.
- Persistencia mediante DataStore.
- Contacto directo con Instagram `@Zaid.nvr`.

## Perfiles por juego

La versión `1.3.0` añade sesiones temporales para:

| Juego | Paquete |
|---|---|
| Free Fire | `com.dts.freefireth` |
| Free Fire MAX | `com.dts.freefiremax` |

La sección muestra el icono real de cada juego instalado, su estado de instalación y los tres perfiles de DPI. Una sesión solo puede comenzar cuando existe un juego y un perfil seleccionados, Shizuku está iniciado, el permiso fue concedido, el paquete está instalado y la notificación de restauración puede mostrarse.

### Flujo de una sesión

1. Bloquea los selectores y comandos de densidad duplicados.
2. Lee WindowManager y persiste un snapshot completo:
   - DPI físico.
   - DPI efectivo.
   - existencia de override.
   - valor exacto del override anterior.
3. Ejecuta mediante Shizuku:

```text
am force-stop PACKAGE_NAME
```

4. Aplica y verifica el DPI seleccionado.
5. Abre la actividad principal del juego o usa `monkey` como respaldo.
6. Espera 1.5 segundos para confirmar el relanzamiento.
7. Mantiene el DPI durante 30 segundos desde un foreground service.
8. Restaura exactamente el estado previo y verifica el resultado.

El flujo final queda así:

```text
Cerrar juego → Aplicar DPI → Verificar DPI → Abrir juego → 30 s → Restaurar DPI anterior
```

### Snapshot exacto

La restauración nunca utiliza un número fijo:

```kotlin
data class DensitySnapshot(
    val physicalDensity: Int,
    val effectiveDensity: Int,
    val hadOverride: Boolean,
    val previousOverrideDensity: Int?
)
```

- Si antes existía un DPI personalizado, se vuelve a aplicar ese mismo valor.
- Si no existía override, se limpia mediante `clearForcedDisplayDensityForUser()`.
- `wm density reset` solo se utiliza como respaldo cuando la llamada Binder no está disponible.

### Foreground service y notificación

`DpiGameSessionService` mantiene la sesión fuera de la Activity y publica una notificación permanente con:

- juego seleccionado;
- perfil y DPI temporal;
- segundos restantes;
- acción **Restaurar ahora**.

La cuenta regresiva se actualiza una vez por segundo. El servicio se detiene y elimina su notificación después de verificar la restauración.

### Rutas de restauración

Todas llaman a la misma lógica central:

- final automático de los 30 segundos;
- botón **Restaurar ahora** dentro de la app;
- acción de la notificación;
- gesto de ambos botones de volumen;
- recuperación al volver a abrir la aplicación;
- reconexión de Shizuku después de un fallo de restauración.

Si Shizuku no está disponible, el snapshot permanece guardado y se muestra:

> No fue posible restaurar el DPI. Inicia Shizuku y pulsa “Restaurar ahora”.

## Por qué 20 DPI no usa `wm density 20`

`WindowManagerShellCommand` normalmente rechaza densidades inferiores a 72. Sensi Ultra utiliza un puente Java ejecutado mediante `app_process` con identidad `shell` de Shizuku:

```java
ServiceManager.getService(Context.WINDOW_SERVICE)
```

El puente convierte el Binder en `android.view.IWindowManager` y usa:

```text
getInitialDisplayDensity(displayId)
getBaseDisplayDensity(displayId)
setForcedDisplayDensityForUser(displayId, density, userId)
clearForcedDisplayDensityForUser(displayId, userId)
```

La aplicación nunca ejecuta `wm size` ni cambia la resolución física.

## Correcciones Liquid Glass 1.3.0

Se corrigió la causa de los rectángulos grises existentes: los drawables de selección combinaban gradientes de alta opacidad y capas decorativas de ancho completo. Ahora cada tarjeta utiliza una única forma redondeada compartida con fondo translúcido y borde fino.

También se corrigió:

- clipping de tarjetas, encabezado e imagen;
- padding superior de secciones;
- separación entre contenedores;
- columnas responsivas con `layout_weight`;
- contraste del texto;
- contenido inferior respetando barras del sistema;
- mensajes de éxito duplicados, ahora mostrados como Snackbar temporal;
- animaciones que se reiniciaban durante cada actualización de la cuenta regresiva;
- carga repetida de iconos, ahora almacenados en caché;
- estado compacto de la prueba de emergencia.

No se emplea blur sobre textos, iconos ni controles. El fallback visual es transparencia, borde, contraste y sombra ligera para evitar artefactos en HyperOS, MIUI, Realme UI, ColorOS y One UI.

## Arquitectura de perfiles por juego

```text
gameprofile/
├── data/
│   ├── GameSessionPreferences.kt
│   └── GameSessionRepositoryImpl.kt
├── domain/
│   ├── SupportedGame.kt
│   ├── GameSessionModels.kt
│   └── GameSessionController.kt
├── service/
│   └── DpiGameSessionService.kt
├── shizuku/
│   ├── ShizukuGameController.kt
│   └── ShizukuCommandExecutor.kt
└── ui/
    └── GameProfileViewModel.kt
```

Los componentes visuales no ejecutan comandos Shizuku. La Activity solo envía intenciones al ViewModel/controlador, y el servicio conserva la restauración incluso cuando el juego deja la app en segundo plano.

## Permisos y visibilidad

El Manifest declara únicamente lo necesario para esta función:

- `FOREGROUND_SERVICE`;
- `POST_NOTIFICATIONS` en Android 13 o superior;
- `VIBRATE`;
- visibilidad específica para Shizuku, Instagram, Free Fire y Free Fire MAX.

No declara `QUERY_ALL_PACKAGES`, permiso de Internet, almacenamiento, ubicación, cámara, micrófono ni contactos.

## Manejo de errores

La app diferencia y muestra mensajes para:

- juego no instalado;
- fallo de `am force-stop`;
- fallo de lanzamiento del juego;
- Shizuku no instalado, detenido o sin permiso;
- Binder desconectado;
- fabricante que bloquea el comando;
- DPI rechazado o no confirmado;
- restauración automática pendiente.

Nunca abre el juego si falló la aplicación o la verificación del DPI y nunca marca una operación como correcta sin releer WindowManager.

## Requisitos

- Android 8.0 o posterior (`minSdk 26`).
- Shizuku instalado e iniciado.
- Permiso de Shizuku concedido.
- Notificaciones autorizadas para iniciar sesiones en Android 13 o superior.
- Servicio de accesibilidad habilitado para el gesto de emergencia.
- Free Fire o Free Fire MAX instalado para usar Perfiles por juego.

## Compilación y validación

GitHub Actions ejecuta:

```bash
./gradlew clean lintDebug testDebugUnitTest assembleDebug
```

La validación automatizada incluye:

- compilación AIDL, Kotlin y Java;
- Lint de recursos y Manifest;
- pruebas unitarias de paquetes, perfiles y semántica del snapshot;
- generación del APK debug.

El APK queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Las comprobaciones reales de lanzamiento, restauración, navegación y 20 DPI dependen del fabricante y deben completarse en un dispositivo físico. La app no simula éxito cuando la ROM rechaza la operación.

## Identidad visual

- `file (1).svg`: logo del encabezado e icono de la aplicación.
- `file.svg`: fondo principal.
- Ambos recursos se convierten a WebP y se validan mediante SHA-256 durante la compilación.

## Licencia

MIT. Consulta `LICENSE`.
