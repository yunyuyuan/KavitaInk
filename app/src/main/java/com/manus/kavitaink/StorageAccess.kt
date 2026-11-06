package com.manus.kavitaink

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 公共下载目录（内部存储/Books/KavitaInk）需要的存储权限：
 *  - Android 11+：「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），在系统设置里手动打开
 *  - Android 10：WRITE_EXTERNAL_STORAGE（配合 requestLegacyExternalStorage）
 */
object StorageAccess {
    const val REQUEST_CODE = 4101

    fun granted(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 ->
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    /** 授权说明的第二段：告诉用户接下来会看到什么 */
    fun stepText(): Int =
        if (Build.VERSION.SDK_INT >= 30) R.string.perm_step_all_files else R.string.perm_step_legacy

    /** 没有权限时的错误提示 */
    fun deniedText(): Int =
        if (Build.VERSION.SDK_INT >= 30) R.string.err_storage_permission else R.string.err_storage_permission_legacy

    /** Android 10 上被「拒绝且不再询问」后，只能去应用详情页手动打开 */
    fun openAppDetails(a: Activity) {
        runCatching {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
        }
    }

    /** 打开授权界面（Android 11+ 是系统设置页，Android 10 是系统弹窗） */
    fun request(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val pkg = Uri.parse("package:" + a.packageName)
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
            } catch (_: ActivityNotFoundException) {
                runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            a.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE)
        }
    }
}
