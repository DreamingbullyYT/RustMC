package dev.horizon.launcher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Thin Android bridge over Horizon's existing Rust Microsoft account implementation. */
object MicrosoftAuth {
    private var loaded = false

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val expiresAt: Long,
        val interval: Long,
    )

    init {
        runCatching {
            System.loadLibrary("horizon_msa")
            loaded = true
        }
    }

    val available: Boolean get() = loaded

    private external fun nativeStart(): String
    private external fun nativeComplete(): String
    private external fun nativeCancel(): String?

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        check(loaded) { "Microsoft sign-in native support is not available in this build." }
        val raw = nativeStart()
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        DeviceCode(
            userCode = json.getString("userCode"),
            verificationUri = json.getString("verificationUri"),
            expiresAt = json.getLong("expiresAt"),
            interval = json.optLong("interval", 5L),
        )
    }

    suspend fun complete(): String = withContext(Dispatchers.IO) {
        check(loaded) { "Microsoft sign-in native support is not available in this build." }
        val raw = nativeComplete()
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        json.optString("name")
    }

    fun cancel() {
        if (loaded) runCatching { nativeCancel() }
    }
}
