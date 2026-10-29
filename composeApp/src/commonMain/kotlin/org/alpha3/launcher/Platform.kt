package org.alpha3.launcher

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform