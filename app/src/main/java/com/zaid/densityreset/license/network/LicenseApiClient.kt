package com.zaid.densityreset.license.network

import com.zaid.densityreset.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LicenseApiResponse(
    val httpCode: Int,
    val body: JSONObject
)

class LicenseNetworkException(
    cause: Throwable
) : IOException(cause)

class LicenseApiClient(
    private val baseUrl: String = BuildConfig.LICENSE_API_URL
) {
    suspend fun activate(
        key: String,
        deviceHash: String,
        appVersionCode: Int,
        packageName: String
    ): LicenseApiResponse = post(
        path = "/license/activate",
        payload = JSONObject()
            .put("key", key)
            .put("deviceHash", deviceHash)
            .put("appVersion", appVersionCode)
            .put("packageName", packageName)
    )

    suspend fun validate(
        licenseToken: String,
        deviceHash: String,
        appVersionCode: Int,
        packageName: String
    ): LicenseApiResponse = post(
        path = "/license/validate",
        payload = JSONObject()
            .put("deviceHash", deviceHash)
            .put("appVersion", appVersionCode)
            .put("packageName", packageName),
        bearerToken = licenseToken
    )

    private suspend fun post(
        path: String,
        payload: JSONObject,
        bearerToken: String? = null
    ): LicenseApiResponse = withContext(Dispatchers.IO) {
        try {
            val endpoint = resolveEndpoint(path)
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                bearerToken?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            connection.disconnect()

            LicenseApiResponse(
                httpCode = status,
                body = if (text.isBlank()) JSONObject() else JSONObject(text)
            )
        } catch (error: Throwable) {
            throw LicenseNetworkException(error)
        }
    }

    private fun resolveEndpoint(path: String): String {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        require(normalizedBase.startsWith("https://")) {
            "License API must use HTTPS."
        }
        return normalizedBase + path
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}
