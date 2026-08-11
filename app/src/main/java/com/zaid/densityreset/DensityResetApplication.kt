package com.zaid.densityreset

import android.app.Application
import com.zaid.densityreset.icons.AppIconRepositoryProvider
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.ui.LicenseUiBinder
import com.zaid.densityreset.quicktile.DensityTileStateObserver
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.startup.StartupActivityGuard
import com.zaid.densityreset.startup.StartupCoordinator
import com.zaid.densityreset.update.UpdateManager

class DensityResetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppIconRepositoryProvider.initialize(this)
        ShizukuManager.initialize(this)
        RemoteConfigManager.initialize(this)
        UpdateManager.initialize(this)
        LicenseManager.initialize(this)
        StartupCoordinator.initialize(this)
        StartupActivityGuard.register(this)
        DensityTileStateObserver.initialize(this)
        LicenseUiBinder.register(this)
    }
}
