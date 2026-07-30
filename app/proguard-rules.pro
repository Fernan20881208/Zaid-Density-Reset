# Shizuku starts this class by its exact name in a separate app_process.
-keep class com.zaid.densityreset.shizuku.PrivilegedDensityService {
    public <init>();
    public <init>(android.content.Context);
    *;
}

# Preserve the AIDL contract and generated Binder implementation.
-keep interface com.zaid.densityreset.IPrivilegedDensityService { *; }
-keep class com.zaid.densityreset.IPrivilegedDensityService$Stub { *; }
-keep class com.zaid.densityreset.IPrivilegedDensityService$Stub$Proxy { *; }
