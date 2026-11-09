package com.headswap.desk.dex

import java.security.MessageDigest
import java.security.SecureRandom

/** Pure validation shared by bootstrap, broker and the owner process. */
object VdmHostPolicy {
    const val PROTOCOL = 1
    const val SHELL_UID = 2000
    const val SHELL_PACKAGE = "com.android.shell"
    const val PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    // Distinct from upstream HeadSwap's 02:48:53:57:41:50. Never match by package alone.
    const val ASSOCIATION_MAC = "02:48:53:44:45:53"
    const val DISPLAY_NAME = "headswap-desk"
    const val METHOD_PREPARE = "prepare"
    const val METHOD_STATUS = "status"
    const val METHOD_PUBLISH = "publish"
    const val EXTRA_APP_PID = "appPid"
    const val EXTRA_NONCE = "launchNonce"
    const val EXTRA_HOST = "host"
    const val EXTRA_HOST_PID = "hostPid"
    const val EXTRA_CLIENT = "client"
    const val EXTRA_READY = "ready"
    const val EXTRA_PROTOCOL = "protocol"
    const val EXTRA_ERROR = "error"
    const val EXTRA_BUILD = "buildCode"
    const val LAUNCH_TTL_MS = 30_000L
    const val MAX_DISPLAYS = 8
    private const val PARSER_PROBE_MAC = "headswap-desk-parser-probe"

    fun nonce(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    fun validNonce(value: String?) = value != null && value.matches(Regex("[0-9a-f]{64}"))
    fun sameSecret(expected: String?, supplied: String?): Boolean = expected != null && supplied != null &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))
    fun launchFresh(created: Long, now: Long) = created >= 0 && now >= created && now - created <= LAUNCH_TTL_MS
    fun ownAssociation(packageName: String?, profile: String?, mac: String?): Boolean =
        packageName == SHELL_PACKAGE && profile == PROFILE && mac.equals(ASSOCIATION_MAC, true)
    fun requireGeometry(width: Int, height: Int, density: Int) {
        require(width in 128..3840 && height in 128..2160 && density in 96..1280) { "VDM display geometry is out of bounds" }
    }
    fun displayFlags(requested: Int): Int {
        // No auto-mirror, secure capture or own group: VDM supplies its device group and lock policy.
        val allowed = 1 or 2 or 8 or 64 or 256 or 1024 or 16384
        return (requested and allowed) or 8 or 1024 or (1 shl 15)
    }
    fun associateCommand(user: Int, named: Boolean): List<String> {
        require(user >= 0)
        val prefix = listOf("/system/bin/cmd", "companiondevice", "associate", user.toString(), SHELL_PACKAGE)
        return prefix + if (named) listOf("--mac-address", ASSOCIATION_MAC, "--profile", PROFILE, "--self-managed", "false")
            else listOf(ASSOCIATION_MAC, PROFILE, "false")
    }
    /** Both platform parsers reject a MAC before reaching association creation. */
    fun parserProbeCommand(user: Int): List<String> {
        require(user >= 0)
        return listOf("/system/bin/cmd", "companiondevice", "associate", user.toString(), SHELL_PACKAGE,
            "--mac-address", PARSER_PROBE_MAC, "--profile", PROFILE)
    }
    fun parserFromProbe(exitCode: Int, output: String): Boolean? {
        if (exitCode == 0 || output.length > 16_384) return null
        val errors = output.lineSequence().map(String::trim).filter {
            it.startsWith("java.lang.IllegalArgumentException:")
        }.toList()
        if (errors.size != 1 || !output.contains("android.net.MacAddress.")) return null
        return when (errors.single()) {
            "java.lang.IllegalArgumentException: --mac-address was not a valid MAC address" -> false
            "java.lang.IllegalArgumentException: $PARSER_PROBE_MAC was not a valid MAC address" -> true
            else -> null
        }
    }
    class Args(val packageName: String, val appPid: Int, val nonce: String) {
        companion object {
            fun parse(args: Array<String>): Args {
                require(args.size == 6)
                val values = args.toList().chunked(2).associate { it[0] to it[1] }
                require(values.keys == setOf("--package", "--app-pid", "--nonce"))
                val pkg = requireNotNull(values["--package"])
                require(pkg == "com.headswap.desk")
                val pid = values["--app-pid"]?.toIntOrNull() ?: error("Invalid app PID")
                require(pid > 0)
                val nonce = requireNotNull(values["--nonce"])
                require(validNonce(nonce))
                return Args(pkg, pid, nonce)
            }
        }
        override fun toString() = "VdmHostArgs(package=$packageName,pid=$appPid,nonce=redacted)"
    }
}
