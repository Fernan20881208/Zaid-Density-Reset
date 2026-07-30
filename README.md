# Density Reset

Aplicación Android en Kotlin que detecta la pulsación simultánea de **Volumen arriba + Volumen abajo durante 2 segundos** y solicita a **Shizuku** ejecutar, con identidad `shell`, un único comando fijo:

```text
/system/bin/wm density reset
```

No usa root, Magisk, Xposed, Internet, almacenamiento, analíticas ni anuncios. El servicio de accesibilidad no recupera contenido de ventanas y no inspecciona otras aplicaciones.

## Funciones

- Gesto global con ambos botones de volumen durante 2 segundos.
- Detección activa aunque la actividad esté cerrada, siempre que accesibilidad y Shizuku continúen activos.
- Protección contra eventos repetidos, doble ejecución y activaciones múltiples antes de soltar ambos botones.
- Opción para consumir los eventos cuando la combinación ya fue reconocida.
- Vibración breve opcional después de una ejecución correcta.
- Botón **Probar Density Reset**, que usa exactamente el mismo UserService.
- Estados visibles de instalación, binder, permiso y conexión del UserService.
- Tema Material 3 claro/oscuro.

## Requisitos

- Android 8.0 o posterior (`minSdk 26`).
- Shizuku instalado e iniciado.
- Permiso de Shizuku concedido a Density Reset.
- Servicio de accesibilidad de Density Reset habilitado para usar el gesto.

## Instalación y uso desde Android

1. Descarga e instala Shizuku desde su repositorio oficial o tienda compatible:
   `https://github.com/RikkaApps/Shizuku/releases/latest`
2. Abre Shizuku y, en un teléfono sin root, inícialo mediante **Depuración inalámbrica** siguiendo el asistente de la propia aplicación.
3. Descarga el APK generado por GitHub Actions e instálalo en el teléfono.
4. Abre **Density Reset**.
5. Pulsa **Solicitar permiso** y concédelo desde Shizuku.
6. Pulsa **Abrir configuración de accesibilidad** y habilita **Atajo de volumen de Density Reset**.
7. Mantén presionados simultáneamente **Volumen arriba** y **Volumen abajo** durante 2 segundos.
8. Para probar sin realizar el gesto, usa **Probar Density Reset**.

En dispositivos sin root, Shizuku normalmente debe volver a iniciarse después de reiniciar el teléfono.

## Descargar el APK desde GitHub Actions

En el repositorio `Fernan20881208/Zaid-Density-Reset`:

1. Abre el repositorio en GitHub.
2. Entra en **Actions**.
3. Abre la ejecución más reciente de **Compilar APK**.
4. Baja hasta **Artifacts**.
5. Descarga **DensityReset-debug-apk**.
6. Abre el ZIP descargado; dentro está `app-debug.apk`.

Ruta generada durante la compilación:

```text
app/build/outputs/apk/debug/app-debug.apk
```

El APK debug queda firmado automáticamente con la clave debug efímera del runner. No se guarda ninguna clave privada en el repositorio.

## Repositorio

Código fuente: `https://github.com/Fernan20881208/Zaid-Density-Reset`

El proyecto se compila completamente en GitHub Actions; no requiere Android Studio ni computadora.

## Arquitectura

### `VolumeShortcutAccessibilityService`

Extiende `AccessibilityService` y solo solicita `flagRequestFilterKeyEvents`. Mantiene estados separados para Volumen arriba y Volumen abajo, inicia un `Handler` de 2 segundos al detectar ambos botones y cancela la tarea cuando cualquiera se libera. La acción se ejecuta una vez por ciclo y no se rearma hasta que ambos botones hayan sido soltados.

Por defecto devuelve `false`, por lo que el sistema conserva el control normal del volumen. Al activar **Bloquear cambios de volumen durante el gesto**, consume los eventos desde el momento en que la combinación ya se reconoce. Android no permite recuperar el primer evento que ya fue entregado, por lo que todavía puede producirse un cambio mínimo antes de reconocer ambos botones.

### `ShizukuManager`

Componente único inicializado por `DensityResetApplication`. Registra los listeners oficiales de binder recibido, binder muerto y resultado del permiso. Comprueba instalación, disponibilidad del binder y permiso; enlaza el UserService; detecta la muerte de su binder; y vuelve a enlazar cuando Shizuku reaparece.

### `IPrivilegedDensityService.aidl`

Contrato Binder mínimo:

```aidl
interface IPrivilegedDensityService {
    void destroy() = 16777114;
    String resetDensity() = 1;
}
```

No expone una consola ni acepta comandos o argumentos del usuario.

### `PrivilegedDensityService`

UserService iniciado por Shizuku con un tag estable y versión. Ejecuta fuera del hilo principal solamente:

```kotlin
ProcessBuilder(
    "/system/bin/wm",
    "density",
    "reset"
)
```

Lee salida estándar y de error de forma concurrente, espera el código de salida, aplica un límite de tiempo, destruye procesos que excedan ese límite y devuelve un JSON estructurado al proceso normal de la aplicación. Implementa el método reservado `destroy()` para limpiar recursos y finalizar el proceso remoto.

## Seguridad y privacidad

- Sin permiso `INTERNET`.
- Sin almacenamiento, ubicación, cámara, micrófono, contactos o notificaciones.
- Sin `QUERY_ALL_PACKAGES`; solo declara una consulta específica para el paquete de Shizuku.
- Sin servicio en primer plano.
- Sin lectura de nodos, ventanas o texto de otras aplicaciones.
- Sin telemetría, anuncios o recopilación de datos.
- El único proceso externo permitido en el código es `/system/bin/wm density reset` con argumentos separados.

## Limitaciones reales

- Algunos fabricantes modifican el manejo de los botones físicos y podrían no entregar todos los eventos al servicio.
- Android solo concede el filtrado de teclas a un servicio de accesibilidad a la vez. Otro servicio habilitado puede impedir que Density Reset reciba el gesto.
- El gesto puede no funcionar con la pantalla bloqueada o apagada.
- Con el bloqueo de teclas desactivado, el volumen puede cambiar durante el gesto.
- Incluso con el bloqueo activado, el primer evento puede llegar al sistema antes de reconocer que ambos botones forman una combinación.
- `wm density reset` puede comportarse de forma distinta en sistemas Android muy modificados o si el fabricante restringe comandos de `shell`.
- Sin root, Shizuku deja de estar activo normalmente después de reiniciar el teléfono.
- Un fabricante puede cerrar el proceso de la aplicación o el servicio de accesibilidad mediante políticas agresivas de batería.

## Compilación

La compilación automática usa:

- Android Gradle Plugin 8.13.2.
- Gradle 8.13.
- Kotlin 2.3.21.
- Java 17.
- `compileSdk` y `targetSdk` 36.
- Material Components 1.14.0 con tema Material 3.
- Shizuku API y Provider 13.1.5.

El workflow se ejecuta en cada `push` a `main` y manualmente con `workflow_dispatch`:

```bash
./gradlew clean assembleDebug
```

## Estructura principal

```text
.github/workflows/build.yml
app/src/main/AndroidManifest.xml
app/src/main/aidl/com/zaid/densityreset/IPrivilegedDensityService.aidl
app/src/main/java/com/zaid/densityreset/MainActivity.kt
app/src/main/java/com/zaid/densityreset/accessibility/VolumeShortcutAccessibilityService.kt
app/src/main/java/com/zaid/densityreset/shizuku/ShizukuManager.kt
app/src/main/java/com/zaid/densityreset/shizuku/PrivilegedDensityService.kt
app/src/main/res/xml/accessibility_service_config.xml
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

## Licencia

MIT. Consulta `LICENSE`.
