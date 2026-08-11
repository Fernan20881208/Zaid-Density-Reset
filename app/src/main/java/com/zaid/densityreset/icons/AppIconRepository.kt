package com.zaid.densityreset.icons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

interface AppIconRepository {
    val invalidationVersion: StateFlow<Long>
    suspend fun getAppIcon(packageName: String): AppIconResult
    fun invalidate(packageName: String? = null)
    fun invalidateForDensityChange()
}

sealed interface AppIconResult {
    data class Success(
        val bitmap: Bitmap,
        val versionCode: Long,
        val lastUpdateTime: Long,
        val method: IconLoadMethod
    ) : AppIconResult

    data object NotFound : AppIconResult

    data class Failure(val message: String) : AppIconResult
}

enum class IconLoadMethod {
    LAUNCHER_ACTIVITY_INFO,
    RESOURCES_FOR_DENSITY,
    APPLICATION_INFO_FALLBACK
}

data class IconCacheKey(
    val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val sourceDensity: Int,
    val targetSizePx: Int,
    val uiMode: Int
)

class AndroidAppIconRepository(context: Context) : AppIconRepository {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val cache = ConcurrentHashMap<IconCacheKey, AppIconResult.Success>()
    private val _invalidationVersion = MutableStateFlow(0L)

    override val invalidationVersion: StateFlow<Long> = _invalidationVersion.asStateFlow()

    override suspend fun getAppIcon(packageName: String): AppIconResult =
        withContext(Dispatchers.IO) {
            val packageInfo = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext AppIconResult.NotFound
            }

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val key = IconCacheKey(
                packageName = packageName,
                versionCode = versionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
                sourceDensity = ICON_SOURCE_DENSITY,
                targetSizePx = ICON_RENDER_SIZE_PX,
                uiMode = appContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            )

            cache[key]?.let { cached ->
                debugLog(
                    "Icon load: package=$packageName method=${cached.method} " +
                        "requestedDensity=$ICON_SOURCE_DENSITY " +
                        "configDensity=${appContext.resources.configuration.densityDpi} cache=HIT"
                )
                return@withContext cached
            }

            cache.keys
                .filter { it.packageName == packageName && it != key }
                .forEach(cache::remove)

            val loaded = loadSourceDrawable(packageName)
                ?: return@withContext AppIconResult.NotFound

            val bitmap = runCatching {
                renderStableBitmap(loaded.drawable)
            }.getOrElse { error ->
                return@withContext AppIconResult.Failure(
                    error.message ?: "No se pudo renderizar el icono."
                )
            }

            val result = AppIconResult.Success(
                bitmap = bitmap,
                versionCode = versionCode,
                lastUpdateTime = packageInfo.lastUpdateTime,
                method = loaded.method
            )
            cache[key] = result
            debugLog(
                "Icon load: package=$packageName method=${loaded.method} " +
                    "requestedDensity=$ICON_SOURCE_DENSITY " +
                    "configDensity=${appContext.resources.configuration.densityDpi} cache=MISS"
            )
            result
        }

    override fun invalidate(packageName: String?) {
        if (packageName == null) {
            cache.clear()
        } else {
            cache.keys.filter { it.packageName == packageName }.forEach(cache::remove)
        }
        notifyInvalidated()
        debugLog("Icon cache invalidated: package=${packageName ?: "ALL"}")
    }

    override fun invalidateForDensityChange() {
        cache.clear()
        notifyInvalidated()
        debugLog("Icon cache invalidated for density change")
    }

    private fun notifyInvalidated() {
        _invalidationVersion.update { current -> current + 1L }
    }

    private fun loadSourceDrawable(packageName: String): LoadedDrawable? {
        findLauncherActivity(packageName)?.let { launcherActivity ->
            runCatching {
                launcherActivity.getIcon(ICON_SOURCE_DENSITY)
            }.getOrNull()?.let { drawable ->
                return LoadedDrawable(drawable, IconLoadMethod.LAUNCHER_ACTIVITY_INFO)
            }
        }

        val applicationInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        if (applicationInfo.icon != 0) {
            try {
                val packageResources = packageManager.getResourcesForApplication(applicationInfo)
                packageResources.getDrawableForDensity(
                    applicationInfo.icon,
                    ICON_SOURCE_DENSITY,
                    null
                )?.let { drawable ->
                    return LoadedDrawable(drawable, IconLoadMethod.RESOURCES_FOR_DENSITY)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Continue to the final framework fallback.
            } catch (_: Resources.NotFoundException) {
                // Continue to the final framework fallback.
            }
        }

        return runCatching {
            LoadedDrawable(
                applicationInfo.loadIcon(packageManager),
                IconLoadMethod.APPLICATION_INFO_FALLBACK
            )
        }.getOrNull()
    }

    private fun findLauncherActivity(packageName: String): LauncherActivityInfo? =
        runCatching {
            launcherApps
                ?.getActivityList(packageName, Process.myUserHandle())
                ?.firstOrNull()
        }.getOrNull()

    private fun renderStableBitmap(source: Drawable): Bitmap {
        val drawable = source.constantState
            ?.newDrawable()
            ?.mutate()
            ?: source.mutate()
        val bitmap = Bitmap.createBitmap(
            ICON_RENDER_SIZE_PX,
            ICON_RENDER_SIZE_PX,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_RENDER_SIZE_PX
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_RENDER_SIZE_PX
        val scale = minOf(
            ICON_RENDER_SIZE_PX.toFloat() / intrinsicWidth,
            ICON_RENDER_SIZE_PX.toFloat() / intrinsicHeight
        )
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        val left = (ICON_RENDER_SIZE_PX - width) / 2
        val top = (ICON_RENDER_SIZE_PX - height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        drawable.draw(canvas)
        return bitmap
    }

    private data class LoadedDrawable(
        val drawable: Drawable,
        val method: IconLoadMethod
    )

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "DensityResetIcons"
        const val ICON_SOURCE_DENSITY = DisplayMetrics.DENSITY_XXXHIGH
        const val ICON_RENDER_SIZE_PX = 384
    }
}

object AppIconRepositoryProvider {
    @Volatile
    private var repository: AppIconRepository? = null
    private var receiverRegistered = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (repository == null) {
            synchronized(this) {
                if (repository == null) {
                    repository = AndroidAppIconRepository(appContext)
                }
            }
        }
        registerPackageReceiver(appContext)
    }

    fun get(context: Context): AppIconRepository {
        initialize(context)
        return checkNotNull(repository)
    }

    fun getOrNull(): AppIconRepository? = repository

    private fun registerPackageReceiver(context: Context) {
        synchronized(this) {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val packageName = intent?.data?.schemeSpecificPart ?: return
                    repository?.invalidate(packageName)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }
}

object DensityIconInvalidationCoordinator {

    suspend fun onDensityChanged(
        context: Context,
        previousDensity: Int,
        expectedDensity: Int,
        hasOverride: Boolean
    ) {
        if (previousDensity == expectedDensity) return
        val appContext = context.applicationContext
        val configurationDensity = awaitConfigurationDensity(
            appContext,
            expectedDensity
        )
        AppIconRepositoryProvider.get(appContext).invalidateForDensityChange()
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Density changed: $previousDensity -> $expectedDensity; " +
                    "configuration=$configurationDensity override=$hasOverride"
            )
        }
    }

    private suspend fun awaitConfigurationDensity(
        context: Context,
        expectedDensity: Int
    ): Int {
        val deadline = System.currentTimeMillis() + CONFIGURATION_TIMEOUT_MILLIS
        var observed = context.resources.configuration.densityDpi
        while (observed != expectedDensity && System.currentTimeMillis() < deadline) {
            delay(CONFIGURATION_POLL_MILLIS)
            observed = context.resources.configuration.densityDpi
        }
        return observed
    }

    private const val TAG = "DensityResetDensity"
    private const val CONFIGURATION_TIMEOUT_MILLIS = 3_000L
    private const val CONFIGURATION_POLL_MILLIS = 50L
}
