package org.alpha3.launcher.utils

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The stock engine's navmesh baking as it goes, straight from the call (Android: NavMeshService,
 * cpp/openmw-upstream/support/src/navmeshbuild.cpp). Null while it is not running: the progress screen then follows
 * navmeshtool.log, which the fork's tool and the Kotlin port write.
 */
object NavMeshStatus {
    val progress = MutableStateFlow<NavMeshBuildProgress?>(null)
}

/**
 * [phase] 0: reading the content files; 1: gathering worldspaces, their tiles made meanwhile ([tilesDone] of [tiles]: those
 * of the worldspaces gathered so far); 2: the tiles left; 3: compacting the database. [result] once it has ended:
 * 0 done, 1 cancelled, 2 out of space, 3 failed.
 */
data class NavMeshBuildProgress(
    val phase: Int,
    val worldspacesDone: Int,
    val worldspaces: Int,
    val tilesDone: Int,
    val tiles: Int,
    val result: Int? = null,
) {
    val fraction: Float
        get() = when {
            result == 0 -> 1f
            phase == 3 -> 0.99f
            tiles > 0 -> (tilesDone.toFloat() / tiles).coerceAtMost(0.99f)
            else -> 0f
        }

    val description: String
        get() = when (result) {
            null -> when (phase) {
                0 -> "Reading the content files..."
                1 -> "Worldspace $worldspacesDone of $worldspaces: $tilesDone / $tiles tiles"
                2 -> "$tilesDone / $tiles tiles"
                else -> "Compacting navmesh.db..."
            }
            0 -> "Done: $tiles tiles"
            1 -> "Cancelled"
            2 -> "Stopped: out of space (or over [Navigator] max navmeshdb file size)"
            else -> "Failed (see navmeshtool.log)"
        }
}
