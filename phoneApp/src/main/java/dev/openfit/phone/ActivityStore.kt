package dev.openfit.phone

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Local storage for received activities + upload to your own stats server (e.g. Dreeve). */
object ActivityStore {
    const val PREFS = "openfit"
    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"

    /** No default server: users must set their own instance URL in the app. */
    const val DEFAULT_BASE_URL = ""
    private const val STATUS_PREFIX = "status_"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activitiesDir(ctx: Context): File = File(ctx.filesDir, "activities").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        activitiesDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun status(ctx: Context, name: String): String =
        prefs(ctx).getString(STATUS_PREFIX + name, null) ?: "pending"

    fun setStatus(ctx: Context, name: String, value: String) {
        prefs(ctx).edit().putString(STATUS_PREFIX + name, value).apply()
    }

    /** Blocking upload of one activity file. Returns a human-readable status string. */
    fun upload(ctx: Context, file: File): String {
        val base = (prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trim().trimEnd('/')
        val key = (prefs(ctx).getString(KEY_API_KEY, "") ?: "").trim()
        if (base.isEmpty()) return "failed: no server URL set"
        if (key.isEmpty()) return "failed: no API key set"

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$base/api/v1/activity/upload")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    "uploaded (HTTP ${resp.code})"
                } else {
                    "failed: HTTP ${resp.code} ${resp.body?.string()?.take(160) ?: ""}"
                }
            }
        } catch (e: Exception) {
            "failed: ${e.message}"
        }
    }

    /** Upload everything that is not marked as uploaded yet. */
    fun syncPending(ctx: Context) {
        list(ctx).forEach { f ->
            val st = status(ctx, f.name)
            if (!st.startsWith("uploaded")) {
                setStatus(ctx, f.name, upload(ctx, f))
            }
        }
    }
}
