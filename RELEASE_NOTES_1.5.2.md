# Density Reset 1.5.2

- Se amplían los perfiles de sensibilidad de 3 a 5 niveles:
  - Sensi Baja · 280 DPI
  - Sensi Media Alta · 176 DPI
  - Sensi Alta · 72 DPI
  - Sensi Muy Alta · 46 DPI
  - Sensi Ultra · 20 DPI
- Sensi Muy Alta y Sensi Ultra usan la ruta WindowManager Binder/Shizuku para densidades inferiores a 72 DPI.
- El Game Launcher, la pantalla heredada y Remote Config soportan los cinco perfiles.
- Se corrige la degradación progresiva de los iconos al usar DPI extremos.
- Los iconos se cargan desde recursos de alta densidad, se rasterizan a una resolución estable y usan una caché segura por versión/paquete.
- La caché de iconos se invalida después de aplicar o restaurar DPI y cuando una aplicación se instala, actualiza, cambia o elimina.
- Se mantienen Game Launcher, sesiones DPI, Quick Settings Tile, sistema de keys, Shizuku, accesibilidad, Remote Config y actualizaciones obligatorias.

## Firma y actualizaciones

Esta versión debe publicarse con una clave de firma Android persistente. No debe publicarse con una clave debug efímera de GitHub Actions, porque futuras actualizaciones Android requieren conservar exactamente la misma firma.
