package com.zaid.densityreset.quicktile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object DensityTileNotifier {
    fun requestRefresh(context: Context) {
        val appContext = context.applicationContext
        listOf(
            DensityQuickTileService::class.java,
            FreeFireQuickTileService::class.java,
            FreeFireMaxQuickTileService::class.java
        ).forEach { service ->
            runCatching {
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, service)
                )
            }
        }
    }
}
