package com.opentokenusage.android.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the per-app "install unknown apps" screen for this package. */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return true

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback) }.isSuccess
    }

    fun install(context: Context, apkFile: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
