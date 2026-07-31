# Density Reset 1.3.0 — Perfiles por juego

## Novedades

- Compatible con Free Fire (`com.dts.freefireth`) y Free Fire MAX (`com.dts.freefiremax`).
- Perfiles Sensi Ultra 20 DPI, Sensi Alta 72 DPI y Sensi Baja 280 DPI.
- Sesiones temporales de 30 segundos con cierre, cambio de DPI, verificación y reapertura del juego.
- Snapshot persistente del DPI físico, efectivo y override anterior.
- Foreground service con cuenta regresiva y acción Restaurar ahora.
- Restauración automática, manual, desde notificación, mediante el gesto de volumen y durante la recuperación de una sesión pendiente.

## Restauración exacta

Si antes existía un DPI personalizado, la app restaura ese mismo valor. Si no existía override, limpia el override y verifica la densidad física. No utiliza un número fijo como restauración.

## Correcciones Liquid Glass

- Eliminados los rectángulos grises opacos de las tarjetas DPI.
- Bordes, fondos y selección comparten el mismo recorte redondeado.
- Espaciados y contraste normalizados.
- Imagen del encabezado sin deformación.
- Mensajes de éxito temporales y no duplicados.
- Animaciones que no se reinician durante cada segundo de la cuenta regresiva.
- Contenido inferior compatible con las barras del sistema.

## Validación

GitHub Actions ejecuta Lint, pruebas unitarias y compilación debug. La validación física final sigue siendo necesaria para confirmar el comportamiento específico de WindowManager y el lanzamiento de los juegos en cada ROM.
