package com.zaid.densityreset.accessibility

object DpiGameLockBridge {
    @Volatile
    private var listener: (() -> Unit)? = null

    val isConnected: Boolean
        get() = listener != null

    fun attach(listener: () -> Unit) {
        this.listener = listener
    }

    fun detach() {
        listener = null
    }

    fun notifySessionChanged() {
        listener?.invoke()
    }
}
