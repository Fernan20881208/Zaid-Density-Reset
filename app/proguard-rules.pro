# Density Reset invokes Shizuku's legacy remote-process bridge by reflection.
# Keep the private method name and its Process wrapper in minified release builds.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# app_process loads this exact entry-point name from the installed APK.
-keep class com.zaid.densityreset.density.DensityBridge {
    public static void main(java.lang.String[]);
    *;
}
