# Density Reset

Aplicación Android en Kotlin para controlar la densidad lógica mediante Shizuku, sin root. El paquete es `com.zaidnavarro.ds`, conserva el diseño Liquid Glass, el gesto global de Volumen arriba + Volumen abajo durante 2 segundos y las sesiones de Free Fire / Free Fire MAX con restauración automática del DPI.

## Funciones actuales

- Selección de Free Fire (`com.dts.freefireth`) o Free Fire MAX (`com.dts.freefiremax`).
- Perfiles por juego: Sensi Ultra 20 DPI, Muy Alta 46 DPI, Alta 72 DPI, Media Alta 176 DPI y Baja 280 DPI.
- 20 DPI mediante Binder interno de WindowManager con identidad `shell` de Shizuku; nunca mediante `wm size`.
- Snapshot exacto del DPI anterior, ventana verificada de 20 segundos, seguimiento de salida del juego y restauración automática/manual.
- Gesto de emergencia con ambos botones de volumen.
- Tiles rápidos independientes `FF` y `FFM`, más atajos dinámicos que usan el perfil DPI predeterminado de cada juego.
- Automatizaciones por juego para DND Prioridad, brillo, bloqueo de rotación y volumen, siempre con snapshot y restauración del valor anterior.
- Grabación opcional mediante MediaProjection: video y audio interno cuando Android y el juego lo permiten, sin capturar el micrófono.
- Tiempo de juego total y por perfil DPI, protección térmica adaptativa y matriz de capacidades reales del dispositivo.
- Notificación compacta tipo isla en el panel del sistema con DPI, batería, temperatura, tiempo, modo y acciones de restauración/grabación.
- Selector global de apariencia con modo AMOLED de negro puro y modo Liquid Glass predeterminado.
- Refracción Liquid Glass real en pantallas View/XML y Compose mediante
  [`QWEA0/Liquid-Glass-Android`](https://github.com/QWEA0/Liquid-Glass-Android), con AGSL en
  Android 13+ y fallback compatible desde API 24.
- Logo `file (1).svg`, fondo `file.svg` y superficies `LiquidGlassView` pasivas detrás de los
  paneles Compose; el contenido y los gestos siguen siendo propiedad de Compose.
- Acceso protegido mediante licencias administradas por servidor.

## Apariencia 1.7.0

La opción se guarda en preferencias y se aplica al arranque, licencia, actualización, Game Launcher
y controles clásicos:

- **Liquid Glass**: opción predeterminada; usa el motor QWEA0 en los paneles de toda la interfaz,
  incluidos los grupos internos de sensibilidad, Game Booster, métricas y selector de apariencia,
  con fondo detallado, refracción dinámica, dispersión moderada, brillo por sensor limitado al
  encabezado y fallback automático de accesibilidad.
- **AMOLED**: fondo `#000000`, barras del sistema negras y paneles opacos oscuros para conservar
  píxeles negros en pantallas OLED. Los controles seleccionados y el HUD de juego también evitan
  fondos azules simulados.

El Game Launcher cambia entre ambos modos inmediatamente, sin recrear la actividad, y mantiene la
selección para el siguiente inicio.

La dependencia de Liquid Glass está fijada a `v2.0.2`. Consulta
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) para la atribución.

## Game Launcher 1.8.0

La versión 1.8.0 conserva los cuatro modos anteriores del Game Booster y agrega `Equilibrado` y
`Ultra ahorro de batería`. Si Android informa temperatura alta, los modos de rendimiento pueden
bajar temporalmente a `Equilibrado`; ante un estado térmico crítico, se intenta `Ultra ahorro de
batería` cuando el modo Battery oficial está disponible. Nunca se desactivan las protecciones
térmicas del sistema.

Los cambios de DND, brillo, rotación, volumen, Game Mode y DPI se guardan antes de aplicarse. Al
confirmar la salida de Free Fire/Free Fire MAX, al restaurar manualmente o al recuperar una sesión
interrumpida, cada subsistema intenta volver al valor exacto anterior. Las grabaciones se guardan
localmente en `Movies/Density Reset`; Android solicita consentimiento de captura en cada sesión.

# Sistema de licencias 1.4.0

La pantalla principal no se muestra hasta que `LicenseGateActivity` termina la validación. La aplicación no contiene una lista de keys y nunca recibe `SUPABASE_SERVICE_ROLE_KEY`, contraseña de administrador ni el secreto de firma.

```text
Android
  │ HTTPS
  ▼
Supabase Edge Function: license-api
  │
  ├── PostgreSQL + RLS
  ├── device binding
  ├── rate limit
  └── tokens firmados

Density Reset Admin
  │ Supabase Auth
  ▼
license-api / PostgreSQL
```

## Keys

Formato:

```text
DR-XXXX-XXXX-XXXX-XXXX
```

El backend normaliza la entrada, genera las keys con `crypto.getRandomValues()` y almacena únicamente `SHA-256(normalizedKey)`, `key_prefix` y `key_suffix`. La key completa se devuelve solamente en la respuesta de creación inicial del administrador.

Duraciones disponibles en el panel: 1, 3, 7, 15, 30, 90, 365 días, permanente y personalizado. El inicio puede ser `first_activation` o `generation`; por defecto es primera activación. Las licencias permanentes usan `expires_at = null`.

## Device binding

Android construye un identificador pseudónimo a partir de:

```text
ANDROID_ID + packageName + app-specific salt
```

y envía únicamente su SHA-256. No utiliza IMEI, teléfono, MAC ni serial restringido. `max_devices` es configurable y por defecto vale 1.

## Android

El módulo `license/` contiene:

```text
license/
├── LicenseManager.kt
├── data/
│   ├── DeviceIdentityProvider.kt
│   ├── LicensePreferences.kt
│   ├── LicenseRepositoryImpl.kt
│   └── SecureLicenseStore.kt
├── domain/
│   └── LicenseModels.kt
├── network/
│   └── LicenseApiClient.kt
├── ui/
│   ├── LicenseGateActivity.kt
│   └── LicenseUiBinder.kt
└── util/
    ├── LicenseKeyFormatter.kt
    └── LicensePolicy.kt
```

El token se cifra con AES/GCM usando una clave no exportable de Android Keystore. DataStore solo guarda estado no sensible: estado, expiración y última validación correcta. La key completa no se conserva después de activar.

La licencia se valida al abrir la app y aproximadamente cada 20 minutos mientras la aplicación permanece activa. La gracia offline por defecto es 12 horas y nunca puede superar `expiresAt` ni la expiración del token.

Códigos soportados:

```text
INVALID_KEY
LICENSE_EXPIRED
LICENSE_REVOKED
LICENSE_DISABLED
DEVICE_LIMIT
DEVICE_MISMATCH
RATE_LIMITED
SERVER_ERROR
NETWORK_ERROR
APP_VERSION_BLOCKED
TOKEN_EXPIRED
INVALID_SESSION
```

## Backend Supabase

Archivos:

```text
supabase/migrations/202608080001_license_system.sql
supabase/migrations/202608080002_license_signing_secret.sql
supabase/functions/license-api/index.ts
supabase/functions/license-api/logic.ts
supabase/functions/license-api/logic.test.ts
supabase/tests/license_system.sql
```

Tablas principales: `licenses`, `license_devices`, `profiles`, `license_rate_limits`, `license_settings`. RLS está habilitado. `anon` y `authenticated` no reciben acceso CRUD directo a `licenses`; las operaciones pasan por la Edge Function usando funciones SQL restringidas a `service_role`.

La función expone:

```text
POST   /license/activate
POST   /license/validate
GET    /admin/dashboard
GET    /admin/licenses
GET    /admin/licenses/:id
POST   /admin/licenses/create
POST   /admin/licenses/create-bulk
POST   /admin/licenses/revoke
POST   /admin/licenses/disable
POST   /admin/licenses/enable
POST   /admin/licenses/reset-device
DELETE /admin/licenses/:id
```

Las rutas administrativas validan primero el JWT de Supabase Auth y después comprueban `profiles.role = 'admin'` en el servidor. La activación está limitada a 5 intentos por minuto por huella derivada de IP + device hash.

El secreto de firma puede proporcionarse como `LICENSE_SIGNING_SECRET`. En el despliegue conectado actual, si esa variable no existe, se obtiene de una fila privada generada criptográficamente por PostgreSQL, inaccesible para `anon` y `authenticated`. Nunca se versiona el valor real.

## Density Reset Admin

El directorio `admin/` contiene el panel web. Usa únicamente la URL de Supabase y una publishable key, ambas credenciales públicas por diseño. El inicio de sesión usa Supabase Auth y todas las acciones importantes vuelven a comprobar el rol admin en `license-api`.

Funciones: dashboard, generación de 1 a 100 keys, copia, CSV, duración personalizada/permanente, búsqueda, filtros, detalles, revocar, deshabilitar, reactivar, resetear dispositivos y eliminar.

La tabla nunca vuelve a mostrar la key completa; usa una forma como:

```text
DR-7K4P-••••-••••-R5TY
```

## Despliegue desde cero

1. Crea un proyecto Supabase.
2. Aplica, en orden, los archivos de `supabase/migrations/`.
3. Despliega `supabase/functions/license-api` como Edge Function. Los endpoints de activación son públicos, por lo que `verify_jwt` de la función puede estar desactivado; las rutas admin realizan autenticación y autorización dentro de la función.
4. Configura los secretos/variables del backend. Nunca los agregues al repositorio:

```text
SUPABASE_URL=
SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=
LICENSE_SIGNING_SECRET=
```

Supabase proporciona automáticamente sus variables estándar a Edge Functions. `LICENSE_SIGNING_SECRET` puede configurarse como secreto dedicado; si se omite, la migración incluida genera el secreto privado dentro de PostgreSQL.

5. Crea un usuario mediante Supabase Auth y agrega su rol:

```sql
insert into public.profiles(user_id, role)
select id, 'admin'
from auth.users
where email = 'TU_CORREO_ADMIN';
```

6. Configura `admin/config.js` usando exclusivamente la URL y publishable/anon key pública y despliega el directorio `admin/` en un host HTTPS.
7. Configura Android con una propiedad Gradle o variable de entorno:

```text
LICENSE_API_URL=https://TU_PROYECTO.supabase.co/functions/v1/license-api
```

El valor se compila como `BuildConfig.LICENSE_API_URL`. No pongas ninguna service-role key en Gradle.

8. Abre Density Reset Admin, inicia sesión y genera la primera key.
9. Instala la APK y prueba activación, validación, cierre de sesión y revocación.

## Configuración del proyecto conectado

El código de esta rama está preparado para:

```text
SUPABASE_URL=https://zzlvupunploglgxbgllm.supabase.co
LICENSE_API_URL=https://zzlvupunploglgxbgllm.supabase.co/functions/v1/license-api
```

La publishable key del panel es pública; las credenciales privilegiadas siguen exclusivamente del lado de Supabase.

## Pruebas

GitHub Actions ejecuta:

```bash
deno test supabase/functions/license-api/logic.test.ts
./gradlew clean lintDebug testDebugUnitTest assembleDebug assembleRelease
```

Además, `supabase/tests/license_system.sql` cubre semántica de activación, expiración, device binding, reset de dispositivo, estados y rate limit a nivel de base de datos. La matriz manual documentada incluye key nueva, incorrecta, expirada, revocada, deshabilitada, permanente, 1/30 días, otro dispositivo, reset, reactivación, servidor sin conexión, gracia offline, token expirado, reinicio, logout, generación individual/masiva y CSV.

## Seguridad

- HTTPS obligatorio en Android.
- No se registra la key, token, device hash completo ni encabezado Authorization.
- La key completa no se guarda en PostgreSQL.
- Android no contiene secretos administrativos.
- RLS habilitado.
- `service_role` se usa únicamente en la Edge Function.
- El panel no contiene contraseña de administrador.
- Cerrar sesión solo borra la sesión local; no libera el dispositivo. El reset se hace desde el panel admin.

## Compilación Android

```bash
./gradlew clean lintDebug testDebugUnitTest assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Licencia del proyecto

MIT. Consulta `LICENSE`.
