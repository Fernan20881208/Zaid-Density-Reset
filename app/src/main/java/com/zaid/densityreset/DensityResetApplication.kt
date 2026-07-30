package com.zaid.densityreset

import android.app.Application
import com.zaid.densityreset.shizuku.ShizukuManager

class DensityResetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.initialize(this)
    }
}
