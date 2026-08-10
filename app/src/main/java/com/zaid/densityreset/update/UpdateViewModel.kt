package com.zaid.densityreset.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UpdateStage {
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY,
    ERROR
}

data class UpdateUiState(
    val release: AppRelease? = null,
    val stage: UpdateStage = UpdateStage.AVAILABLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int? = null,
    val verifiedUpdate: VerifiedUpdate? = null,
    val message: String? = null
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    fun setRelease(release: AppRelease) {
        if (_uiState.value.release?.releaseId == release.releaseId) return
        downloadJob?.cancel()
        _uiState.value = UpdateUiState(
            release = release,
            stage = UpdateStage.AVAILABLE,
            message = if (release.apkUrl.isBlank()) {
                "Vuelve a comprobar GitHub para obtener el APK oficial de esta versión."
            } else {
                null
            }
        )
        if (release.apkUrl.isNotBlank()) {
            viewModelScope.launch {
                val verified = UpdateManager.verifiedDownloadedFile(release)
                if (verified != null) {
                    _uiState.value = _uiState.value.copy(
                        stage = UpdateStage.READY,
                        verifiedUpdate = verified,
                        progressPercent = 100,
                        downloadedBytes = verified.file.length(),
                        totalBytes = verified.file.length(),
                        message = "Actualización verificada y lista para instalar."
                    )
                }
            }
        }
    }

    fun download() {
        val release = _uiState.value.release ?: return
        if (release.apkUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                stage = UpdateStage.ERROR,
                message = "No hay un APK oficial disponible todavía. Reintenta la comprobación."
            )
            return
        }
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            UpdateManager.downloadUpdate(release).collect { state ->
                when (state) {
                    DownloadState.Idle -> Unit
                    is DownloadState.Downloading -> {
                        _uiState.value = _uiState.value.copy(
                            stage = UpdateStage.DOWNLOADING,
                            downloadedBytes = state.downloadedBytes,
                            totalBytes = state.totalBytes,
                            progressPercent = state.progressPercent,
                            message = null
                        )
                    }
                    is DownloadState.Downloaded -> {
                        _uiState.value = _uiState.value.copy(
                            stage = UpdateStage.VERIFYING,
                            downloadedBytes = state.file.length(),
                            totalBytes = state.file.length(),
                            progressPercent = 100,
                            message = "Verificando actualización oficial…"
                        )
                        when (val verified = UpdateManager.verifyDownloaded(release, state.file)) {
                            is ApkVerificationResult.Valid -> {
                                _uiState.value = _uiState.value.copy(
                                    stage = UpdateStage.READY,
                                    verifiedUpdate = verified.update,
                                    message = "Actualización verificada y lista para instalar."
                                )
                            }
                            is ApkVerificationResult.Invalid -> {
                                _uiState.value = _uiState.value.copy(
                                    stage = UpdateStage.ERROR,
                                    verifiedUpdate = null,
                                    message = "No se pudo verificar la actualización. ${verified.message}"
                                )
                            }
                        }
                    }
                    is DownloadState.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            stage = UpdateStage.ERROR,
                            message = state.message
                        )
                    }
                    DownloadState.Cancelled -> {
                        _uiState.value = _uiState.value.copy(
                            stage = UpdateStage.ERROR,
                            message = "La descarga fue cancelada o interrumpida."
                        )
                    }
                }
            }
        }
    }

    fun install(context: Context) {
        val verified = _uiState.value.verifiedUpdate ?: return
        viewModelScope.launch {
            when (val result = UpdateManager.installVerified(context, verified)) {
                UpdateManager.InstallLaunchResult.InstallerOpened -> {
                    _uiState.value = _uiState.value.copy(
                        message = "Instalación lista. Completa la actualización en Android."
                    )
                }
                UpdateManager.InstallLaunchResult.PermissionRequested -> {
                    _uiState.value = _uiState.value.copy(
                        message = "Autoriza a Density Reset como origen de instalación y vuelve a pulsar INSTALAR."
                    )
                }
                is UpdateManager.InstallLaunchResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        stage = UpdateStage.ERROR,
                        verifiedUpdate = null,
                        message = result.message
                    )
                }
            }
        }
    }
}
