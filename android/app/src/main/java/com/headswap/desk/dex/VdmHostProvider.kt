package com.headswap.desk.dex

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Shell can publish only against a fresh challenge issued by the live activated main process. */
class VdmHostProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != VdmHostPolicy.SHELL_UID) throw SecurityException("VDM bootstrap requires shell")
        val values = requireNotNull(extras)
        val pid = values.getInt(VdmHostPolicy.EXTRA_APP_PID, -1)
        return when (method) {
            VdmHostPolicy.METHOD_PREPARE -> VdmHostLink.prepare(arg, pid)
            VdmHostPolicy.METHOD_STATUS -> VdmHostLink.status(arg, pid, values.getString(VdmHostPolicy.EXTRA_NONCE))
            VdmHostPolicy.METHOD_PUBLISH -> {
                check(values.getInt(VdmHostPolicy.EXTRA_BUILD, -1) == com.headswap.desk.BuildConfig.VERSION_CODE) { "VDM build mismatch" }
                VdmHostLink.accept(arg, pid, values.getInt(VdmHostPolicy.EXTRA_HOST_PID, -1),
                    requireNotNull(values.getBinder(VdmHostPolicy.EXTRA_HOST)), Binder.getCallingPid())
            }
            else -> throw IllegalArgumentException("Unknown VDM bootstrap method")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
