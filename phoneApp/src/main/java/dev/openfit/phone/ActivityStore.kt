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
    const val KEY_THEME = "theme_mode"

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

    /**
     * Drop absurd sensor values from a TCX (e.g. Health Services' Double.MAX_VALUE "no value"
     * sentinel): they overflow to INF server-side and abort that activity's metrics pipeline.
     */
    private val NUMERIC_ELEMENT =
        Regex("""<(AltitudeMeters|DistanceMeters|LatitudeDegrees|LongitudeDegrees|Value)>([^<]+)</\1>""")

    fun sanitizeTcx(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.UTF_8)
        if (!text.contains("<Trackpoint>")) return bytes
        var dropped = 0
        val cleaned = NUMERIC_ELEMENT.replace(text) { m ->
            val v = m.groupValues[2].trim().toDoubleOrNull()
            if (v == null || !v.isFinite() || kotlin.math.abs(v) >= 1e30) {
                dropped++
                ""
            } else {
                m.value
            }
        }
        if (dropped == 0) return bytes
        return cleaned.toByteArray(Charsets.UTF_8)
    }

    /** Blocking upload of one activity file. Returns a human-readable status string. */
    fun upload(ctx: Context, file: File): String {
        val base = (prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trim().trimEnd('/')
        val key = (prefs(ctx).getString(KEY_API_KEY, "") ?: "").trim()
        if (base.isEmpty()) return "failed: no server URL set"
        if (key.isEmpty()) return "failed: no API key set"

        // Never send (or keep) a file containing sensor sentinels — clean it in place first.
        try {
            val raw = file.readBytes()
            val sane = sanitizeTcx(raw)
            if (!raw.contentEquals(sane)) file.writeBytes(sane)
        } catch (_: Exception) {
            // best effort: the upload below still runs with whatever the file holds
        }

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

    /** Upload everything that is not marked as uploaded yet. Returns a one-line summary. */
    fun syncPending(ctx: Context): String {
        val pending = list(ctx).filter { !status(ctx, it.name).startsWith("uploaded") }
        if (pending.isEmpty()) return "nothing pending"
        var ok = 0
        var failed = 0
        pending.forEach { f ->
            val result = upload(ctx, f)
            setStatus(ctx, f.name, result)
            if (result.startsWith("uploaded")) ok++ else failed++
        }
        return "synced $ok${if (failed > 0) ", $failed failed" else ""}"
    }

    /** Check the configured server: GET {base}/api/v1/status with the API key. */
    fun testConnection(ctx: Context): String {
        val base = (prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trim().trimEnd('/')
        val key = (prefs(ctx).getString(KEY_API_KEY, "") ?: "").trim()
        if (base.isEmpty()) return "no server URL set"
        if (key.isEmpty()) return "no API key set"
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("$base/api/v1/status")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) "connected ✓" else "failed: HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            "failed: ${e.message?.take(120)}"
        }
    }
}
