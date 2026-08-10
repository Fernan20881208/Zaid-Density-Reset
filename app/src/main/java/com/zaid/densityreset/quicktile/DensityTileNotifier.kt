package com.zaid.densityreset.quicktile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object DensityTileNotifier {
    fun requestRefresh(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, DensityQuickTileService::class.java)
            )
        }
    }
}
