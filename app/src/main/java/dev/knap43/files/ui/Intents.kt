package dev.knap43.files.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.knap43.files.data.FileOps
import java.io.File

private fun Context.uriFor(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

/** Returns false when no installed app can handle the file. */
fun Context.openFile(file: File): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(file), FileOps.mimeType(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

fun Context.shareFiles(files: List<File>) {
    val shareable = files.filter { it.isFile }
    if (shareable.isEmpty()) return
    val uris = shareable.map { uriFor(it) }
    val mimes = shareable.map { FileOps.mimeType(it) }.distinct()
    val mime = mimes.singleOrNull()?.takeIf { it != "*/*" }
        ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" }
        ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        type = mime
        clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null))
}
