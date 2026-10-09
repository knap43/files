package dev.knap43.files.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

data class FileItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int?,
)

data class StorageRoot(val label: String, val dir: File, val removable: Boolean)

enum class SortMode(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date modified"), TYPE("Type") }

enum class FileKind { DIR, IMAGE, VIDEO, AUDIO, PDF, ARCHIVE, APK, TEXT, OTHER }

object FileOps {

    fun list(dir: File, showHidden: Boolean): List<FileItem> {
        val children = dir.listFiles() ?: throw IOException("Can't read ${dir.name}")
        return children
            .asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toItem(showHidden) }
            .toList()
    }

    fun File.toItem(showHidden: Boolean = true): FileItem {
        val dir = isDirectory
        return FileItem(
            file = this,
            name = name,
            isDir = dir,
            size = if (dir) 0L else length(),
            modified = lastModified(),
            childCount = if (dir) list()?.count { showHidden || !it.startsWith(".") } else null,
        )
    }

    fun sort(items: List<FileItem>, mode: SortMode, ascending: Boolean): List<FileItem> {
        val byMode: Comparator<FileItem> = when (mode) {
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.SIZE -> compareBy<FileItem> { if (it.isDir) (it.childCount ?: 0).toLong() else it.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.DATE -> compareBy<FileItem> { it.modified }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.TYPE -> compareBy<FileItem> { it.file.extension.lowercase() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val directed = if (ascending) byMode else byMode.reversed()
        // Folders always stay above files, whichever direction the sort runs.
        return items.sortedWith(compareBy<FileItem> { !it.isDir }.then(directed))
    }

    /** Picks "name (1).ext", "name (2).ext"… when [name] already exists in [dir]. */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0 && !File(dir, name).isDirectory
        val base = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        var n = 1
        while (target.exists()) target = File(dir, "$base ($n)$ext").also { n++ }
        return target
    }

    fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p.trimEnd('/') + "/")
    }

    fun copyInto(src: File, destDir: File): File {
        val target = uniqueTarget(destDir, src.name)
        if (!src.copyRecursively(target, overwrite = false)) throw IOException("Copy failed: ${src.name}")
        return target
    }

    fun moveInto(src: File, destDir: File): File {
        val target = uniqueTarget(destDir, src.name)
        if (src.renameTo(target)) return target
        // Different volume: fall back to copy + delete.
        if (!src.copyRecursively(target, overwrite = false)) throw IOException("Move failed: ${src.name}")
        if (!src.deleteRecursively()) throw IOException("Moved, but couldn't remove the original ${src.name}")
        return target
    }

    fun mimeType(file: File): String {
        if (file.isDirectory) return "resource/folder"
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "epub" -> "application/epub+zip"
            "md", "kt", "kts", "gradle", "log", "conf", "ini", "toml", "yaml", "yml" -> "text/plain"
            else -> "*/*"
        }
    }

    fun kind(item: FileItem): FileKind {
        if (item.isDir) return FileKind.DIR
        val ext = item.file.extension.lowercase()
        if (ext == "apk") return FileKind.APK
        if (ext in setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst")) return FileKind.ARCHIVE
        val mime = mimeType(item.file)
        return when {
            mime.startsWith("image/") -> FileKind.IMAGE
            mime.startsWith("video/") -> FileKind.VIDEO
            mime.startsWith("audio/") -> FileKind.AUDIO
            mime == "application/pdf" -> FileKind.PDF
            mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    fun roots(context: Context): List<StorageRoot> {
        val primary = StorageRoot("Internal storage", Environment.getExternalStorageDirectory(), removable = false)
        val others = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.storageVolumes.filter { !it.isPrimary }.mapNotNull { v ->
                v.directory?.let { StorageRoot(v.getDescription(context), it, v.isRemovable) }
            }
        } else {
            // App-specific dirs look like /storage/XXXX-XXXX/Android/data/<pkg>/files; trim to the volume root.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().mapNotNull { dir ->
                val path = dir.absolutePath
                val idx = path.indexOf("/Android/data/")
                if (idx > 0) StorageRoot("SD card", File(path.substring(0, idx)), removable = true) else null
            }
        }
        return listOf(primary) + others.filter { it.dir.canRead() }
    }
}
