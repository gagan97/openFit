package dev.openfit.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives finished activity files from the watch (Wearable Data Layer),
 * stores them locally and uploads them to Dreeve.
 */
class ActivityReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        try {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem ?: continue
                val path = item.uri.path ?: continue
                if (!path.startsWith(PREFIX)) continue

                val name = item.data?.toString(Charsets.UTF_8) ?: path.substringAfterLast('/')
                val asset = item.assets["file"] ?: continue

                scope.launch {
                    try {
                        val dataClient = Wearable.getDataClient(this@ActivityReceiverService)
                        val resp = Tasks.await(dataClient.getFdForAsset(asset))
                        val bytes = resp.inputStream?.use { it.readBytes() }
                        if (bytes == null || bytes.isEmpty()) return@launch

                        val f = File(ActivityStore.activitiesDir(this@ActivityReceiverService), name)
                        f.writeBytes(bytes)
                        ActivityStore.setStatus(this@ActivityReceiverService, name, "received - uploading…")

                        val result = ActivityStore.upload(this@ActivityReceiverService, f)
                        ActivityStore.setStatus(this@ActivityReceiverService, name, result)

                        // The file is safe on the phone now; clear it from the data layer.
                        runCatching { Tasks.await(dataClient.deleteDataItems(item.uri)) }
                    } catch (e: Exception) {
                        ActivityStore.setStatus(this@ActivityReceiverService, name, "failed: ${e.message}")
                    }
                }
            }
        } finally {
            runCatching { (events as? java.io.Closeable)?.close() }
        }
    }

    private companion object {
        const val PREFIX = "/openfit/activity/"
    }
}
