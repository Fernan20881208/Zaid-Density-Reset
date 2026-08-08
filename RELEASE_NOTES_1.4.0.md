# Density Reset 1.4.0 — Licencias y panel de administración

## Acceso mediante keys

- La pantalla principal queda protegida por una pantalla de activación Liquid Glass.
- Las keys usan el formato `DR-XXXX-XXXX-XXXX-XXXX` y se validan exclusivamente contra el backend.
- Android no contiene una lista de keys ni secretos administrativos.
- El token de licencia se almacena cifrado con Android Keystore.
- El identificador del dispositivo se deriva localmente y únicamente se envía como SHA-256.
- Validación al abrir, revalidación periódica y gracia offline de 12 horas sin exceder la expiración real.

## Backend Supabase

- PostgreSQL con RLS.
- Edge Function `license-api` para activación, validación y acciones administrativas.
- Device binding configurable y rate limit de 5 activaciones por minuto por IP + device hash.
- Keys guardadas únicamente como SHA-256; la key completa solo se devuelve inmediatamente después de generarla.
- Tokens firmados del lado servidor y renovados durante la validación.

## Density Reset Admin

- Login mediante Supabase Auth.
- Rol `admin` verificado por el servidor.
- Dashboard, búsqueda, filtros, detalle, generación individual o masiva, CSV, revocar, deshabilitar, reactivar, resetear dispositivos y eliminar.

## Compatibilidad

No se elimina ninguna función de Density Reset: Shizuku, perfiles por juego, DPI 20/72/280, foreground service de 30 segundos, restauración automática, notificación y gesto de volumen permanecen intactos.
