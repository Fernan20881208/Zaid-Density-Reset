# Density Reset

Aplicación Android en Kotlin para controlar la densidad lógica de Android mediante Shizuku, sin root. Conserva un gesto global de emergencia: mantener **Volumen arriba + Volumen abajo durante 2 segundos** restablece inmediatamente el DPI, incluso cuando la actividad no está visible, siempre que Shizuku y el servicio de accesibilidad continúen activos.

La interfaz utiliza el estilo visual **Liquid Glass** del proyecto y el paquete publicado es `com.zaidnavarro.ds`.

## Funciones

- Perfiles de DPI múltiple:
  - **Sensi Ultra — 20 DPI:** sensibilidad extrema.
  - **Sensi Alta — 72 DPI:** sensibilidad alta.
  - **Sensi Baja — 280 DPI:** sensibilidad estable.
- Lectura del DPI físico, DPI activo y estado real del override.
- Detección automática de perfil activo, DPI personalizado o DPI original.
- Aplicación directa mediante el Binder interno de WindowManager con identidad `shell` de Shizuku.
- Confirmación especial para 20 DPI que exige mantener pulsado el botón durante 1.5 segundos.
- Botón visible de restablecimiento de emergencia.
- Gesto global de restablecimiento con ambos botones de volumen durante 2 segundos.
- Persistencia con DataStore de densidad original, último perfil, último DPI, override y fecha del cambio.
- Verificación posterior de cada aplicación y restablecimiento; no muestra éxito si la ROM rechazó el valor.
- Vibración breve opcional después de un restablecimiento correcto.
- Contacto directo con Instagram `@Zaid.nvr`.

## Identidad visual

La versión `1.2.1` conserva la interfaz Liquid Glass y sustituye exclusivamente sus recursos de marca:

- `file (1).svg` se utiliza como logo del encabezado y como icono de la aplicación.
- `file.svg` se utiliza como fondo principal de la pantalla.
- Ambos SVG se renderizan como WebP optimizados y su SHA-256 se comprueba durante la compilación para evitar recursos incompletos o alterados.

Este cambio visual no modifica el controlador de densidad, Binder de WindowManager, Shizuku, DataStore ni el servicio de accesibilidad.

## Por qué 20 DPI no usa `wm density 20`

`WindowManagerShellCommand` valida el valor antes de enviarlo al servicio y normalmente rechaza densidades inferiores a 72. Por eso Sensi Ultra no se implementa ejecutando el comando estándar.

Density Reset mantiene el proceso remoto de Shizuku ya usado por el proyecto y ejecuta un puente Java mínimo mediante `app_process`. Ese proceso corre con la identidad `shell`, obtiene el Binder con:

```java
ServiceManager.getService(Context.WINDOW_SERVICE)
```

y llama directamente a la interfaz interna `android.view.IWindowManager`:

```text
getInitialDisplayDensity(displayId)
getBaseDisplayDensity(displayId)
setForcedDisplayDensityForUser(displayId, density, userId)
clearForcedDisplayDensityForUser(displayId, userId)
```

No modifica la resolución física y nunca ejecuta `wm size`.

## Restablecimiento y respaldos

El restablecimiento principal usa:

```text
clearForcedDisplayDensityForUser(Display.DEFAULT_DISPLAY, currentUserId)
```

Si la API Binder no está disponible en una ROM concreta, el único respaldo de restablecimiento es:

```text
wm density reset
```

Para lectura, si las APIs internas no están disponibles, la app analiza `Physical density` y `Override density` de la salida de `wm density`.

Los perfiles de 72 y 280 DPI pueden usar `wm density VALOR` únicamente como respaldo cuando el acceso Binder no está disponible. El perfil de 20 DPI nunca usa ese respaldo, porque sería rechazado por la validación del comando.

## Protección de Sensi Ultra

20 DPI puede reducir de forma extrema el tamaño de la interfaz. Antes de aplicarlo:

1. La app consulta y guarda la densidad original.
2. Muestra una advertencia explícita.
3. Exige mantener presionado **Aplicar Sensi Ultra** durante aproximadamente 1.5 segundos.
4. Conserva el servicio de accesibilidad y el gesto de emergencia.
5. Verifica que WindowManager realmente reporte 20 DPI antes de marcar el perfil como activo.

## Requisitos

- Android 8.0 o posterior (`minSdk 26`).
- Shizuku instalado e iniciado.
- Permiso de Shizuku concedido a Density Reset.
- Servicio de accesibilidad habilitado para usar el gesto de emergencia.

En dispositivos sin root, Shizuku normalmente debe volver a iniciarse después de reiniciar el teléfono.

## Uso

1. Inicia Shizuku mediante depuración inalámbrica o el método compatible con tu dispositivo.
2. Abre Density Reset y concede su autorización en Shizuku.
3. Habilita **Atajo de volumen de Density Reset** en Accesibilidad.
4. En **DPI múltiple**, selecciona Sensi Ultra, Sensi Alta o Sensi Baja.
5. Comprueba el mensaje de verificación y el estado activo.
6. Para volver al valor original, usa **Restablecer DPI de emergencia** o mantén ambos botones de volumen durante 2 segundos.

## Arquitectura

### `ShizukuManager`

Conserva el canal remoto directo que ya funcionaba en el proyecto. Gestiona instalación, Binder, autorización y el comando fijo de restablecimiento heredado.

### `DensityController`

Abstracción de lectura, aplicación y restablecimiento:

```kotlin
interface DensityController {
    suspend fun getInitialDensity(): Int
    suspend fun getCurrentDensity(): Int
    suspend fun applyDensity(density: Int): Result<Unit>
    suspend fun resetDensity(): Result<Unit>
}
```

### `ShizukuDensityController`

Valida Shizuku, inicia el puente Binder mediante el proceso remoto existente, interpreta respuestas, aplica respaldos permitidos y verifica el DPI real.

### `DensityBridge`

Entry point Java cargado mediante `app_process` desde el APK instalado. No acepta comandos arbitrarios: solo `status`, `apply` y `reset`. Habla directamente con `IWindowManager` y devuelve una respuesta estructurada de una sola línea.

### `DensityViewModel`

Mantiene el estado de UI, serializa operaciones, consulta el sistema al abrir la app y solo activa visualmente un perfil tras comprobar el valor real.

### `DensityPreferencesRepository`

Usa Preferences DataStore para guardar:

- Densidad original.
- Último perfil seleccionado.
- Último DPI aplicado.
- Existencia de override.
- Fecha y hora del último cambio.

### `VolumeShortcutAccessibilityService`

Solo filtra eventos de los botones de volumen. No solicita contenido de ventanas ni inspecciona otras aplicaciones. El gesto ejecuta el mismo controlador de densidad y persiste el restablecimiento.

## Manejo de errores

La app distingue y muestra mensajes entendibles para:

- Shizuku no instalado o detenido.
- Permiso denegado.
- Binder desconectado.
- `RemoteException` o `SecurityException`.
- API interna no disponible.
- Densidad rechazada.
- Bloqueo del fabricante.
- Cambio no reflejado en la lectura posterior.

Nunca marca un perfil como activo cuando la verificación falla.

## Compatibilidad

El acceso interno puede variar entre AOSP, HyperOS, MIUI, Realme UI, ColorOS y One UI. La app informa el error real y mantiene disponible el restablecimiento; no simula éxito en ROMs que bloquean la operación.

## Seguridad y privacidad

- Sin permiso `INTERNET`.
- Sin root, Magisk o Xposed.
- Sin anuncios, telemetría o analíticas.
- Sin almacenamiento, ubicación, cámara, micrófono o contactos.
- Sin lectura de pantalla.
- Sin consola ni comandos introducidos por el usuario.
- No usa `settings put secure display_density_forced -1`.
- No cambia la resolución física.

## Compilación

GitHub Actions ejecuta:

```bash
./gradlew clean assembleDebug
```

El APK queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

La versión `1.2.1` actualiza el logo y el fondo desde los SVG proporcionados y se publica automáticamente como Release al fusionar el commit de lanzamiento en `main`.

## Archivos principales

```text
app/src/main/java/com/zaid/densityreset/MainActivity.kt
app/src/main/java/com/zaid/densityreset/accessibility/VolumeShortcutAccessibilityService.kt
app/src/main/java/com/zaid/densityreset/density/DensityBridge.java
app/src/main/java/com/zaid/densityreset/density/DensityController.kt
app/src/main/java/com/zaid/densityreset/density/DensityPreset.kt
app/src/main/java/com/zaid/densityreset/density/DensityPreferencesRepository.kt
app/src/main/java/com/zaid/densityreset/density/DensityViewModel.kt
app/src/main/java/com/zaid/densityreset/density/ShizukuDensityController.kt
app/src/main/res/layout/view_density_panel.xml
app/src/main/res/layout/dialog_ultra_confirmation.xml
```

## Licencia

MIT. Consulta `LICENSE`.
