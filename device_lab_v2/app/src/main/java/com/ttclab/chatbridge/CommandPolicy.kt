package com.ttclab.chatbridge

object CommandPolicy {
    private val sensitiveTokens = listOf(
        "bank", "wallet", "billing", "payment", "vending", "authenticator",
        "password", "credential", "keychain", "keystore"
    )

    fun isSensitivePackage(packageName: String): Boolean {
        val value = packageName.trim().lowercase()
        return sensitiveTokens.any { it in value }
    }

    fun isAllowedForeground(foregroundPackage: String?, allowedPackage: String): Boolean {
        if (foregroundPackage.isNullOrBlank()) return false
        if (allowedPackage.isBlank()) return false
        return foregroundPackage == allowedPackage && !isSensitivePackage(foregroundPackage)
    }

    fun isPointInside(x: Float, y: Float, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && x in 0f..width.toFloat() && y in 0f..height.toFloat()

    fun isSwipeInside(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        width: Int,
        height: Int
    ): Boolean = isPointInside(x1, y1, width, height) && isPointInside(x2, y2, width, height)
}
