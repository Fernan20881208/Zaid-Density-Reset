# Density Reset 1.7.0

## Apariencia global

- Se añade un selector persistente con dos modos: `Liquid Glass` y `AMOLED`.
- `Liquid Glass` permanece como opción predeterminada y utiliza refracción, desenfoque dinámico y dispersión moderada en las pantallas View/XML.
- `AMOLED` utiliza fondo y barras del sistema en negro puro, con paneles opacos oscuros para conservar los píxeles negros de pantallas OLED.
- La apariencia elegida se conserva entre reinicios y se aplica al arranque, licencia, actualización, Game Launcher y controles clásicos.

## Liquid Glass nativo

- Se integra `QWEA0/Liquid-Glass-Android` `v2.0.2` para los paneles View/XML.
- El fondo refractivo sigue actualizándose durante el desplazamiento.
- El resaltado por sensores se limita al encabezado para reducir trabajo duplicado.
- El modo automático de accesibilidad y ahorro de energía de la biblioteca permanece activo.
- Las pantallas Compose conservan el mismo lenguaje visual mediante componentes Compose nativos.

## Compatibilidad

- Se conserva Android 8.0+ (`minSdk 26`) y el objetivo Android 16 (`targetSdk 36`).
- Se mantiene el flujo Shizuku-first y la restauración exacta del DPI previo a cada sesión.

## Versión

- versionName: `1.7.0`
- versionCode: `18`
