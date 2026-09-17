package dev.openfit.wear

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Upload queue drainer. Files approved with Done stay in the local queue until Dreeve confirms
 * them (dedupe on the server makes retries idempotent) — nothing is ever lost, and the Settings
 * screen exposes a manual "Sync now" for whatever a dead network left behind.
 */
object SyncManager {

    data class SyncState(
        val running: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val message: String? = null,
        val lastRunMillis: Long = 0L,
    )

    val state = MutableStateFlow(SyncState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    /** Upload every approved-but-not-uploaded file. Safe to call repeatedly. */
    fun syncNow(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        state.value = state.value.copy(running = true, message = null)
        scope.launch {
            try {
                val dir = WearPrefs.activitiesDir(ctx)
                val pending = WearPrefs.approvedPending(ctx, dir)
                val base = WearPrefs.base(ctx)
                val key = WearPrefs.apiKey(ctx)
                if (pending.isEmpty()) {
                    finish(ctx, "nothing pending")
                    return@launch
                }
                if (base == null || key == null) {
                    finish(ctx, "Dreeve not configured", pending.size)
                    return@launch
                }
                var ok = 0
                var failed = 0
                pending.forEachIndexed { i, f ->
                    state.value = state.value.copy(running = true, progress = i, total = pending.size)
                    val err = DreeveUploader.upload(f, base, key)
                    if (err == null) {
                        WearPrefs.markUploaded(ctx, f.name)
                        ok++
                    } else {
                        failed++
                    }
                }
                finish(
                    ctx,
                    buildString {
                        append("$ok uploaded")
                        if (failed > 0) append(" · $failed failed")
                    },
                    pending.size,
                )
            } catch (e: Exception) {
                finish(ctx, "sync error: ${e.message}")
            } finally {
                inFlight.set(false)
                refreshBusCounts(ctx)
            }
        }
    }

    private fun finish(ctx: Context, message: String, total: Int = 0) {
        state.value = state.value.copy(
            running = false,
            progress = total,
            total = total,
            message = message,
            lastRunMillis = System.currentTimeMillis(),
        )
        refreshBusCounts(ctx)
    }

    private fun refreshBusCounts(ctx: Context) {
        runCatching {
            val dir = WearPrefs.activitiesDir(ctx)
            RecorderBus.state.value = RecorderBus.state.value.copy(
                pending = WearPrefs.approvedPending(ctx, dir).size,
                review = WearPrefs.unreviewed(ctx, dir).size,
            )
        }
    }
}
