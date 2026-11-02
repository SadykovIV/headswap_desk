package com.headswap.desk.embed

import com.headswap.desk.dex.DesktopModePolicy
import com.headswap.desk.dex.EmbeddedDisplayPolicy

/** Pure argument bounds; caller identity and live session generation are checked by the service. */
object EmbeddedGatewayPolicy {
    const val EXPECTED_CALLER_PACKAGE = DesktopModePolicy.DRIVEDECK_PACKAGE
    const val MAX_LEASES = 2

    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    fun requireParent(mode: Int, requestedParentDisplayId: Int, ownedParentDisplayId: Int, ownDesktop: Boolean = false) {
        val requiredMode = if (ownDesktop) DesktopModePolicy.ANDROID_DESKTOP else DesktopModePolicy.DRIVEDECK
        require(mode == requiredMode) { "Parent does not belong to this desktop profile" }
        require(requestedParentDisplayId > 0 && ownedParentDisplayId > 0 &&
            requestedParentDisplayId == ownedParentDisplayId) { "Parent display is not the owned desktop" }
    }

    fun requireGeometry(width: Int, height: Int, density: Int) {
        EmbeddedDisplayPolicy.requireSize(width, height, density)
    }

    /** Count includes leases being created or retired until their producer is confirmed closed. */
    fun requireCanAllocate(activeLeaseCount: Int) {
        require(activeLeaseCount in 0 until MAX_LEASES) { "No embedded display lease is available" }
    }

    /** Accepts full class names, including classes whose namespace differs from the APK package. */
    fun requireComponent(packageName: String, className: String) {
        require(packagePattern.matches(packageName) && classPattern.matches(className)) {
            "Expected an explicit package and full activity class name"
        }
    }
}
