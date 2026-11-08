package org.alpha3.launcher.cs

import org.alpha3.launcher.tools.render.parseNif
import org.alpha3.launcher.tools.render.readBlocks
import java.io.File
import kotlin.test.Test

class NifProbeTest {
    @Test
    fun probe() {
        val f = System.getenv("NIF_PROBE")?.let(::File)?.takeIf { it.isFile } ?: return
        val (bl, err) = readBlocks(f.readBytes())
        println("blocks=${bl.size} err=$err " + bl.take(12).joinToString { it.type + ":" + it.name + ":" + it.children.size })
        val m = parseNif(f.readBytes(), includeSkinned = true)
        println("meshes=${m.meshes.size} error=${m.error} min=${m.min.toList()} max=${m.max.toList()}")
        for (x in m.meshes) println("  ${x.name} verts=${x.positions.size / 3} tris=${x.indices.size / 3} tex=${x.texture}")
    }
}
