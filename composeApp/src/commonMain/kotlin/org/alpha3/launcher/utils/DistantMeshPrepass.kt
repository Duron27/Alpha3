package org.alpha3.launcher.utils

/**
 * Makes every distant mesh and tree card of the mod list into the stock OpenMW engine's cache before the game, so a first
 * visit to an area loads like a later one (Android: CacheBuildService, cpp/openmw-upstream/support/src/cachebuild.cpp).
 */
interface DistantMeshPrepassRunner {
    /** [onProgress] is called a few times a second until the run ends. */
    suspend fun run(onProgress: (DistantMeshPrepassProgress) -> Unit): DistantMeshPrepassResult

    fun cancel()
}

data class DistantMeshPrepassProgress(val done: Int, val total: Int, val made: Int, val notMade: Int)

enum class DistantMeshPrepassResult { Done, Cancelled, Off, Failed }
