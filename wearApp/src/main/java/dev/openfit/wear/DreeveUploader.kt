package dev.openfit.wear

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Uploads a finished activity file straight from the watch to the Dreeve instance. */
object DreeveUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Blocking. @return null on success, otherwise a short human-readable error. */
    fun upload(file: File, base: String, apiKey: String): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$base/api/v1/activity/upload")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) null
                else "HTTP ${resp.code} ${resp.body?.string()?.take(120) ?: ""}".trim()
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
