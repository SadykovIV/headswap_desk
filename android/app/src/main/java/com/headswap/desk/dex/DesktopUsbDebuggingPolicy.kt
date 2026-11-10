package com.headswap.desk.dex

/** USB reconfiguration must be able to restart the shell host's parent, adbd. */
internal object DesktopUsbDebuggingPolicy {
    enum class State { ENABLED, DISABLED, UNKNOWN }

    fun required(sdk: Int, delegated: Boolean, nativeDex: Boolean, wifiOnly: Boolean): Boolean =
        sdk >= 37 && delegated && !nativeDex && !wifiOnly

    fun state(setting: String?, usbAdb: Boolean?): State = when {
        usbAdb == true || setting?.trim() == "1" -> State.ENABLED
        setting?.trim() == "0" -> State.DISABLED
        else -> State.UNKNOWN
    }

    fun hasAdbFunction(functions: String?): Boolean =
        functions.orEmpty().split(',').any { it.trim() == "adb" }

    fun verifiedState(setting: String?, functions: String?): State =
        state(setting, null).takeUnless { it == State.UNKNOWN }
            ?: state(null, hasAdbFunction(functions))

    /** App-side settings may be filtered by a firmware. Only the authenticated shell can reject setup. */
    data class Confirmation(val appSetting: String?, val shellState: State) {
        fun stillMissing(currentAppSetting: String?, usbAdb: Boolean?): Boolean =
            shellState == State.DISABLED && currentAppSetting == appSetting &&
                usbAdb != true
    }
}
