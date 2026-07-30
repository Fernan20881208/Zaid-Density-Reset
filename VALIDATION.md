# Validación del proyecto

El proyecto incluye comprobaciones estructurales previas a la compilación:

- Paquete principal coherente: `com.zaid.densityreset`.
- AIDL ubicado en la ruta correspondiente a su paquete.
- Servicio de accesibilidad declarado con `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- Configuración con `flagRequestFilterKeyEvents`, `canRequestFilterKeyEvents=true` y `canRetrieveWindowContent=false`.
- UserService de Shizuku con tag y versión estables.
- Único comando externo permitido: `/system/bin/wm density reset`, construido con argumentos separados.
- Sin permisos de Internet, almacenamiento, notificaciones ni `QUERY_ALL_PACKAGES`.
- Gradle Wrapper, Java 17 y workflow de GitHub Actions incluidos.

La ejecución de **Compilar APK** en GitHub Actions es la validación definitiva de compilación y genera el artifact `DensityReset-debug-apk`.
