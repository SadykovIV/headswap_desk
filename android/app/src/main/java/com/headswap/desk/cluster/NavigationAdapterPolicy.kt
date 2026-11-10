package com.headswap.desk.cluster

import com.headswap.desk.aa.AaMeta

/** A snapshot is atomic: never combine a road from one step with another step's direction. */
internal data class NavigationAdapterSample(
    val protocol: Int, val session: String, val sequence: Long, val sampledAt: Long, val display: Int,
    val state: String, val routeEpoch: Long, val step: String, val action: String, val distance: Int, val road: String,
)

internal class NavigationAdapterPolicy {
    private var session = ""
    private var sequence = 0L
    private var routeEpoch = 0L

    fun accepts(sample: NavigationAdapterSample, now: Long, allowedDisplays: Set<Int>): Boolean {
        if (sample.protocol != 1 || sample.session.length !in 1..64 || sample.sequence <= 0 ||
            sample.display <= 0 || sample.display !in allowedDisplays || now - sample.sampledAt !in 0..2000 ||
            sample.routeEpoch < 0 || sample.step.length > 128 || sample.road.length > 100 ||
            sample.road.any(Char::isISOControl)) return false
        if (sample.session == session && (sample.sequence <= sequence || sample.routeEpoch < routeEpoch)) return false
        session = sample.session; sequence = sample.sequence; routeEpoch = sample.routeEpoch
        return true
    }

    fun hint(sample: NavigationAdapterSample, includeRoad: Boolean): ClusterNavigationHint? {
        if (sample.state != "guidance" || sample.routeEpoch <= 0 || sample.step.isEmpty() || sample.distance !in 0..1_000_000) return null
        val turn = when (sample.action) {
            "STRAIGHT" -> AaMeta.T_FRONT
            "LEFT" -> AaMeta.T_LEFT
            "RIGHT" -> AaMeta.T_RIGHT
            "SLIGHT_LEFT" -> AaMeta.T_LEFT_FRONT
            "SLIGHT_RIGHT" -> AaMeta.T_RIGHT_FRONT
            "HARD_LEFT" -> AaMeta.T_LEFT_BACK
            "HARD_RIGHT" -> AaMeta.T_RIGHT_BACK
            "UTURN_LEFT", "UTURN_RIGHT" -> AaMeta.T_BACK
            "FORK_LEFT" -> AaMeta.T_BRANCH_LEFT
            "FORK_RIGHT" -> AaMeta.T_BRANCH_RIGHT
            "EXIT_LEFT" -> AaMeta.T_LEFT_SIDE
            "EXIT_RIGHT" -> AaMeta.T_RIGHT_SIDE
            "ENTER_ROUNDABOUT" -> AaMeta.T_RING
            "LEAVE_ROUNDABOUT" -> AaMeta.T_RING_OUT
            "FINISH" -> AaMeta.T_DEST
            else -> return null
        }
        return ClusterNavigationHint(turn, if (includeRoad) sample.road.trim() else "", sample.distance)
    }
}
