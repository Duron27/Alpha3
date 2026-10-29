package org.alpha3.launcher.utils

/** Minimal android.net.Uri stand-in: the app only stores and compares file locations with it. */
class Uri(private val value: String) {
    /** Path component without scheme or query. */
    val path: String get() = value.removePrefix("file://").substringBefore('?')

    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is Uri && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

fun String.toUri(): Uri = Uri(this)
