package com.zaid.densityreset

import android.app.Application
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.ui.LicenseUiBinder
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.startup.StartupCoordinator
import com.zaid.densityreset.update.UpdateManager

class DensityResetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.initialize(this)
        RemoteConfigManager.initialize(this)
        UpdateManager.initialize(this)
        LicenseManager.initialize(this)
        StartupCoordinator.initialize(this)
        LicenseUiBinder.register(this)
    }
}
