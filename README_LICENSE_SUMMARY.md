## Sistema de licencias 1.4.0

Density Reset incluye un sistema de acceso mediante keys validado por Supabase. La Activity principal no es el launcher: `LicenseGateActivity` valida una sesión existente o solicita una key antes de abrir el contenido. Las keys no se almacenan en texto plano en PostgreSQL y los secretos administrativos no forman parte del APK.

Consulta [LICENSE_SETUP.md](LICENSE_SETUP.md) para crear Supabase, aplicar migraciones, desplegar la Edge Function, crear un administrador, configurar el panel, conectar Android, generar la primera key y ejecutar las pruebas.
