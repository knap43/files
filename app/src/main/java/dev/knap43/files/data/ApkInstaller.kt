package dev.knap43.files.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs APK files through [PackageInstaller]. The system shows its own
 * confirmation; the outcome arrives in [InstallResultReceiver] and is
 * published on [results] as a message for the user.
 */
object ApkInstaller {
    private const val EXTRA_LABEL = "label"

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val results: SharedFlow<String> = _results

    /** Whether the user has let this app install others ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** "App name 1.2.3" read from the archive, or the file name when it can't be parsed. */
    fun describe(context: Context, apk: File): String {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
            ?: return apk.name
        val app = info.applicationInfo ?: return apk.name
        app.sourceDir = apk.absolutePath
        app.publicSourceDir = apk.absolutePath
        val label = runCatching { app.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: info.packageName
        return listOfNotNull(label, info.versionName).joinToString(" ")
    }

    /** Copies the APK into an install session and commits it; returns once the system has taken over. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val label = describe(app, apk)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val callback = Intent(app, InstallResultReceiver::class.java).putExtra(EXTRA_LABEL, label)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, id, callback, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "The app"
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val message = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                "Couldn't ask to install $label"
            }
            PackageInstaller.STATUS_SUCCESS -> "Installed $label"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation of $label cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installing $label was blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "$label conflicts with an installed version (different signature or a newer version)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "$label isn't compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "This isn't a valid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install $label"
            else -> "Couldn't install $label" + (detail?.let { ": $it" } ?: "")
        }
        _results.tryEmit(message)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstaller.onResult(context, intent)
}

/** True for files [ApkInstaller] can install. */
fun File.isApk(): Boolean = isFile && extension.equals("apk", ignoreCase = true)
