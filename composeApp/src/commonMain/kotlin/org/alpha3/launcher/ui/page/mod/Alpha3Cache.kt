package org.alpha3.launcher.ui.page.mod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.alpha3.launcher.utils.DistantMeshPrepassResult
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.utils.TexturePrepassResult

/**
 * "Build Alpha3 cache" (Mod tools): what the stock engine otherwise makes the first time an area is visited, made ahead
 * for the whole mod list. The textures first (the texture pre-pass), then every distant mesh and tree card. Not the
 * navmesh: openmw-navmeshtool takes too long, and the game keeps each area's navmesh once it has built it. The run
 * outlives the screen; it shows again when the screen is opened.
 */
object Alpha3Cache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    private var cancelled = false

    val available: Boolean get() = Platform.features.distantMeshPrepass != null

    fun start() {
        val meshes = Platform.features.distantMeshPrepass ?: return
        val textures = Platform.features.texturePrepass
        running = true
        cancelled = false
        scope.launch {
            val summary = mutableListOf<String>()
            try {
                if (textures != null) {
                    status = "Textures: starting"
                    var converted = 0
                    var cached = 0
                    val result = textures.run { p ->
                        converted = p.converted
                        cached = p.cached
                        status = if (p.listing) "Textures: listing data directories, ${p.done} of ${p.total}"
                        else "Textures: ${p.done} of ${p.total}, $converted converted, $cached already cached"
                    }
                    summary += when (result) {
                        TexturePrepassResult.Done -> "textures: $converted converted, $cached already cached"
                        TexturePrepassResult.ConversionOff -> "textures: conversion is off in settings.cfg"
                        TexturePrepassResult.Busy -> "textures: already being converted"
                        TexturePrepassResult.Cancelled -> "textures: stopped"
                    }
                }
                if (!cancelled) {
                    status = "Distant meshes: starting"
                    var last: org.alpha3.launcher.utils.DistantMeshPrepassProgress? = null
                    val result = meshes.run { p ->
                        last = p
                        status = "Distant meshes: ${p.done} of ${p.total}, ${p.made} made"
                    }
                    val done = last
                    summary += when (result) {
                        DistantMeshPrepassResult.Done ->
                            "distant meshes: ${done?.made ?: 0} made, ${(done?.done ?: 0) - (done?.made ?: 0) - (done?.notMade ?: 0)} already cached, " +
                                "${done?.notMade ?: 0} left as they are"
                        DistantMeshPrepassResult.Cancelled -> "distant meshes: stopped, what was made is kept"
                        DistantMeshPrepassResult.Off -> "distant meshes: off in settings.cfg ([Terrain] impostors and distant mesh simplification)"
                        DistantMeshPrepassResult.Failed -> "distant meshes: failed (see Alpha3Cache.log)"
                    }
                }
                status = "Done. " + summary.joinToString("; ").replaceFirstChar { it.uppercase() }
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        cancelled = true
        Platform.features.texturePrepass?.cancel()
        Platform.features.distantMeshPrepass?.cancel()
    }
}
