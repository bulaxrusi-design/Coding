package com.ttclab.chatbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test
    fun exactAllowlistedPackageIsRequired() {
        val allowed = "com.easybrain.number.puzzle.game"
        assertTrue(CommandPolicy.isAllowedForeground(allowed, allowed))
        assertFalse(CommandPolicy.isAllowedForeground("com.easybrain.number.puzzle.game.fake", allowed))
        assertFalse(CommandPolicy.isAllowedForeground(null, allowed))
    }

    @Test
    fun sensitivePackagesAreBlocked() {
        assertTrue(CommandPolicy.isSensitivePackage("com.example.wallet"))
        assertTrue(CommandPolicy.isSensitivePackage("com.vendor.authenticator.app"))
        assertFalse(CommandPolicy.isSensitivePackage("com.easybrain.number.puzzle.game"))
    }

    @Test
    fun pointsAndSwipesMustStayOnScreen() {
        assertTrue(CommandPolicy.isPointInside(0f, 0f, 1080, 2200))
        assertTrue(CommandPolicy.isPointInside(1080f, 2200f, 1080, 2200))
        assertFalse(CommandPolicy.isPointInside(-1f, 10f, 1080, 2200))
        assertFalse(CommandPolicy.isPointInside(10f, 2201f, 1080, 2200))
        assertTrue(CommandPolicy.isSwipeInside(10f, 10f, 100f, 100f, 1080, 2200))
        assertFalse(CommandPolicy.isSwipeInside(10f, 10f, 2000f, 100f, 1080, 2200))
    }
}
