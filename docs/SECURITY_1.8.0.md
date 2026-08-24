# Seguridad y privacidad — 1.8.0

- MediaProjection se inicia únicamente después de una acción del usuario y del diálogo de Android.
  El token se usa una vez y no se persiste.
- `RECORD_AUDIO` se utiliza exclusivamente para AudioPlaybackCapture. No se configura una fuente
  de micrófono y la interfaz lo indica antes de jugar.
- La captura de audio se restringe al UID de Free Fire/Free Fire MAX y a usos GAME/MEDIA. Android y
  la aplicación fuente pueden impedirla; en ese caso se conserva video solamente.
- Los MP4 se guardan localmente mediante MediaStore y no se suben al backend.
- DND, brillo, rotación y volumen se aplican solo si el usuario los habilita para ese juego. Antes
  de cada cambio se persiste un snapshot restaurable.
- Los tiles y atajos pasan por `StartupActivity`: no omiten licencia, mantenimiento ni actualización
  obligatoria.
- La isla es una notificación foreground compacta. No solicita overlay; el permiso overlay queda
  limitado al HUD opcional existente.
- Los modos térmicos nunca desactivan throttling, límites de temperatura ni protecciones del OEM.
