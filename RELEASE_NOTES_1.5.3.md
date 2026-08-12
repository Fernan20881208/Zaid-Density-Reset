# Density Reset 1.5.3

- Se corrige el flujo de sensibilidad por juego para que el DPI seleccionado se aplique realmente antes de abrir Free Fire o Free Fire MAX.
- La app verifica el valor real reportado por WindowManager después de aplicar el perfil; si no coincide, la sesión falla en lugar de aparentar que la sensibilidad está activa.
- Una vez confirmado el DPI, comienza un temporizador absoluto de 20 segundos.
- Al terminar los 20 segundos se ejecuta literalmente `wm density reset` mediante Shizuku y se verifica que el override desapareció y el dispositivo volvió a su densidad física.
- Salir del juego ya no adelanta el restablecimiento: la sensibilidad dura los 20 segundos completos salvo que el usuario use Restaurar ahora o el atajo de emergencia.
- Accesibilidad deja de ser un requisito para iniciar la sensibilidad; se conserva para el atajo de volumen y apoyo durante la sesión.
- Sensi Muy Alta (46 DPI) y Sensi Ultra (20 DPI) conservan la ruta WindowManager Binder/Shizuku para aplicar valores inferiores a 72 DPI.
- El temporizador se persiste como una hora absoluta, por lo que reiniciar el servicio no extiende la ventana de 20 segundos.

## Actualización

Esta versión mantiene la firma release persistente introducida en 1.5.2, por lo que 1.5.2 puede actualizarse normalmente a 1.5.3.
