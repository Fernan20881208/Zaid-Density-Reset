# Density Reset 1.2.0 — DPI múltiple

## Novedades

- Nuevo selector Liquid Glass con tres perfiles verificados:
  - Sensi Ultra: 20 DPI.
  - Sensi Alta: 72 DPI.
  - Sensi Baja: 280 DPI.
- Acceso directo al Binder interno de WindowManager mediante el proceso remoto existente de Shizuku.
- 20 DPI evita la validación mínima de `wm density` sin requerir root.
- Confirmación mantenida durante 1.5 segundos para Sensi Ultra.
- Lectura del DPI físico, DPI activo y override real.
- Estado automático: perfil activo, DPI personalizado o DPI original.
- Persistencia con DataStore del valor original, último perfil y fecha del cambio.
- Botón visible de restablecimiento de emergencia.
- El gesto Volumen arriba + Volumen abajo durante 2 segundos sigue restableciendo el DPI con la app cerrada.
- Mensajes claros para permisos, bloqueos de ROM, Binder desconectado y fallos de verificación.
- Restaurado el logo exacto proporcionado por Zaid.

## Seguridad

Sensi Ultra puede hacer que toda la interfaz del sistema se vea extremadamente pequeña. Antes de usarla, habilita el servicio de accesibilidad y comprueba que el gesto de emergencia funciona. Density Reset no cambia la resolución física ni ejecuta `wm size`.

## Instalación

El paquete sigue siendo `com.zaidnavarro.ds`, por lo que puede instalarse sobre Density Reset 1.1.0 cuando la firma sea compatible. Los APK debug creados por runners distintos pueden usar firmas diferentes; en ese caso es necesario desinstalar la versión anterior y volver a conceder Shizuku y Accesibilidad.
