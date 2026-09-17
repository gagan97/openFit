package dev.openfit.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

/**
 * Sends a finished activity file to the paired phone via the Wearable Data Layer.
 * The file travels as an asset (streamed, not limited to the 100 KB data payload),
 * the filename as a small data payload. The phone stores it and uploads to Dreeve.
 */
object DataLayerSender {
    const val PATH_PREFIX = "/openfit/activity/"

    fun sendActivityFile(context: Context, filename: String, bytes: ByteArray): Task<DataItem> {
        val request = PutDataRequest.create(PATH_PREFIX + filename)
            .setData(filename.toByteArray(Charsets.UTF_8))
            .putAsset("file", Asset.createFromBytes(bytes))
            .setUrgent()
        return Wearable.getDataClient(context).putDataItem(request)
    }
}
