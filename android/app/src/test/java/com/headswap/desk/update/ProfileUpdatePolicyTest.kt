package com.headswap.desk.update

import org.junit.Assert.*
import org.junit.Test

class ProfileUpdatePolicyTest {
    @Test fun installedPublisherAndExactTargetDecideUpdate() {
        val signers = setOf("a", "b")
        fun check(pkg: String = "org.panel", pins: Set<String> = signers, version: Long = 11, compatible: Boolean = true) =
            ProfileUpdatePolicy.checkArchive("org.panel", signers, 10, pkg, pins, version, compatible)
        check()
        assertThrows(IllegalStateException::class.java) { check(pkg = "org.other") }
        assertThrows(IllegalStateException::class.java) { check(pins = setOf("a")) }
        assertThrows(IllegalStateException::class.java) { check(pins = setOf("c")) }
        assertThrows(IllegalStateException::class.java) { check(version = 10) }
        assertThrows(IllegalStateException::class.java) { check(version = 9) }
        assertThrows(IllegalStateException::class.java) { check(compatible = false) }
    }

    @Test fun optionalReleasePageOpensOnlyAsHttpsWithoutEmbeddedCredentials() {
        val page = "https://publisher.example/app/releases"
        assertEquals(page, ProfileUpdatePolicy.publisherPage(page))
        listOf(null, 23, "", "https://", "http://example.org/a", "intent://x", "file:///tmp/update",
            "https://example.org@unrelated.example/x", "https://publisher.example:8080/files",
            "https://publisher.example/x\nurl").forEach { assertNull(ProfileUpdatePolicy.publisherPage(it)) }
    }
}
