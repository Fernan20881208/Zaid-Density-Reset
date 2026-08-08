package com.zaid.densityreset

import android.app.Application
import com.zaid.densityreset.license.LicenseManager
import com.zaid.densityreset.license.ui.LicenseUiBinder
import com.zaid.densityreset.shizuku.ShizukuManager

class DensityResetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.initialize(this)
        LicenseManager.initialize(this)
        LicenseUiBinder.register(this)
    }
}
