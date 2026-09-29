package dev.homedroid

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File

/**
 * A mounted storage volume: internal shared storage, an SD card or a USB drive.
 *
 * Before Android 11, apps can't write outside their own folder on secondary volumes (SD
 * cards, USB drives), even with the storage permission; only [appDir] is writable there. Such
 * volumes are [appDirOnly].
 */
class Volume(val label: String, val root: File, val removable: Boolean, val appDir: File?, val appDirOnly: Boolean) {
    val free get() = root.freeSpace
}

/**
 * Where apps keep their bulk data (media, photos). By default that is private storage inside
 * the app and needs no permission; a visible folder on any volume needs "All files access"
 * (Android 11+) or the storage permission (Android 10).
 */
object Storage {
    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val appDirs = ctx.getExternalFilesDirs(null).filterNotNull()
        return sm.storageVolumes.mapNotNull { v ->
            if (v.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val root = when {
                Build.VERSION.SDK_INT >= 30 -> v.directory
                v.isPrimary -> @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
                else -> v.uuid?.let { File("/storage/$it") }
            } ?: return@mapNotNull null
            val appDir = appDirs.firstOrNull { it.path.startsWith(root.path + "/") }
            val appDirOnly = Build.VERSION.SDK_INT < 30 && !v.isPrimary
            if (appDirOnly && appDir == null) return@mapNotNull null
            Volume(v.getDescription(ctx), root, v.isRemovable, appDir, appDirOnly)
        }
    }

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun requestAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
        } else {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE,
            )
        }
    }

    /** The folder [app] would use on [volume]. */
    fun folderOn(volume: Volume, app: AppDef) =
        if (volume.appDirOnly) File(volume.appDir, app.storageFolder)
        else File(volume.root, "Homedroid/${app.storageFolder}")

    /** Whether using [volume] needs [requestAccess] first. */
    fun needsAccess(volume: Volume) = !volume.appDirOnly

    const val REQUEST_CODE = 42
}
