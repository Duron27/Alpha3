package org.alpha3.launcher.tools.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Scenes built from stock Morrowind objects, each lit by the two light kinds the settings control. */
internal enum class PreviewScene(val label: String) {
    NIGHT_WATCH("Night Watch"),
    DESK_CANDLE("Desk Candle"),
    DWEMER_WORKSHOP("Dwemer Workshop"),
    CAMPFIRE("Campfire"),
    WINE_CELLAR("Wine Cellar"),
    BEDROOM("Bedroom"),
    ALCHEMIST("Alchemist")
}

internal class LoadedScene(
    val meshes: List<SceneMesh>,
    val lights: List<SceneLight>,
    val ambient: FloatArray,
    val fog: FloatArray,
    val fogDensity: Float,
    val target: FloatArray,
    val yaw: Float,
    val pitch: Float,
    val dist: Float,
    val missing: List<String>,
    val actor: GuardActor? = null
)

private class Placement(
    val model: String,
    val x: Float, val y: Float, val z: Float,
    val yawDeg: Float = 0f,
    val scale: Float = 1f,
    /** true: z is where the object's lowest point rests; false: z is the model origin. */
    val rest: Boolean = true
)

private class Quad(
    val texture: String,
    val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float,
    val x2: Float, val y2: Float, val z2: Float,
    val x3: Float, val y3: Float, val z3: Float,
    val uvScale: Float,
    val ground: Boolean = false
)

private fun quadMesh(assets: MwAssets, q: Quad): SceneMesh {
    val pos = floatArrayOf(q.x0, q.y0, q.z0, q.x1, q.y1, q.z1, q.x2, q.y2, q.z2, q.x3, q.y3, q.z3)
    val ax = q.x1 - q.x0; val ay = q.y1 - q.y0; val az = q.z1 - q.z0
    val bx = q.x3 - q.x0; val by = q.y3 - q.y0; val bz = q.z3 - q.z0
    var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
    val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    nx /= l; ny /= l; nz /= l
    val lenA = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
    val lenB = kotlin.math.sqrt(bx * bx + by * by + bz * bz)
    val uvs = floatArrayOf(0f, 0f, lenA / q.uvScale, 0f, lenA / q.uvScale, lenB / q.uvScale, 0f, lenB / q.uvScale)
    return SceneMesh(
        pos, FloatArray(12) { listOf(nx, ny, nz)[it % 3] }, uvs, null,
        intArrayOf(0, 1, 2, 0, 2, 3), assets.readTexture(q.texture),
        floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f), false, false, q.ground
    )
}

private fun loadModel(assets: MwAssets, p: Placement, missing: MutableList<String>, cache: MutableMap<String, MwModel>, textures: MutableMap<String, Bitmap?>): List<SceneMesh> {
    val model = cache.getOrPut(p.model) {
        val bytes = assets.read(p.model)
        if (bytes == null) MwModel(emptyList(), "missing") else parseNif(bytes)
    }
    if (model.meshes.isEmpty()) { missing += p.model; return emptyList() }

    val yaw = (p.yawDeg * PI / 180.0)
    val c = cos(yaw).toFloat(); val s = sin(yaw).toFloat()
    val lift = if (p.rest) p.z - model.min[2] * p.scale else p.z

    return model.meshes.map { m ->
        val n = m.positions.size / 3
        val wp = FloatArray(n * 3)
        val wn = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = m.positions[i * 3] * p.scale; val y = m.positions[i * 3 + 1] * p.scale; val z = m.positions[i * 3 + 2] * p.scale
            wp[i * 3] = p.x + c * x - s * y
            wp[i * 3 + 1] = p.y + s * x + c * y
            wp[i * 3 + 2] = lift + z
            val nx = m.normals[i * 3]; val ny = m.normals[i * 3 + 1]
            wn[i * 3] = c * nx - s * ny
            wn[i * 3 + 1] = s * nx + c * ny
            wn[i * 3 + 2] = m.normals[i * 3 + 2]
        }
        val tex = m.texture?.let { name -> textures.getOrPut(name.lowercase()) { assets.readTexture(name) } }
        SceneMesh(
            wp, wn, m.uvs, m.colors, m.indices, tex,
            if (m.diffuse.all { it == 0f }) floatArrayOf(1f, 1f, 1f) else m.diffuse, m.emissive,
            m.alphaBlend, m.alphaTest
        )
    }
}

/** A floor plus back and side walls, centered on x=0 with the back wall at y=300. */
private fun room(assets: MwAssets, meshes: MutableList<SceneMesh>, floor: String, wall: String, half: Float, height: Float, uv: Float) {
    meshes += quadMesh(assets, Quad(floor, -half, -500f, 0f, half, -500f, 0f, half, 300f, 0f, -half, 300f, 0f, uv, ground = true))
    meshes += quadMesh(assets, Quad(wall, -half, 300f, 0f, half, 300f, 0f, half, 300f, height, -half, 300f, height, uv))
    meshes += quadMesh(assets, Quad(wall, -half, -500f, 0f, -half, 300f, 0f, -half, 300f, height, -half, -500f, height, uv))
    meshes += quadMesh(assets, Quad(wall, half, 300f, 0f, half, -500f, 0f, half, -500f, height, half, 300f, height, uv))
}

internal fun loadPreviewScene(assets: MwAssets, scene: PreviewScene): LoadedScene {
    val missing = ArrayList<String>()
    val cache = HashMap<String, MwModel>()
    val textures = HashMap<String, Bitmap?>()
    val meshes = ArrayList<SceneMesh>()
    fun add(p: Placement) { meshes += loadModel(assets, p, missing, cache, textures) }

    return when (scene) {
        PreviewScene.NIGHT_WATCH -> {
            meshes += quadMesh(assets, Quad("tx_cobblestone_01.dds", -1400f, -1400f, 0f, 1400f, -1400f, 0f, 1400f, 900f, 0f, -1400f, 900f, 0f, 256f, ground = true))
            add(Placement("meshes/x/ex_common_house_tall_01.nif", 0f, 0f, 0f))
            add(Placement("meshes/x/ex_common_entrance_01.nif", 0f, -328f, 0f))
            add(Placement("meshes/f/furn_com_torch_ring_01.nif", -190f, -300f, 150f, 0f, 1f, false))
            add(Placement("meshes/l/light_com_torch_01.nif", -190f, -312f, 150f, 0f, 1f, false))
            add(Placement("meshes/f/furn_com_kegstand.nif", 230f, -380f, 0f, 20f))
            add(Placement("meshes/f/furn_crate_open_01.nif", 290f, -330f, 0f, 10f))
            add(Placement("meshes/f/furn_halfbarrel00.nif", -300f, -380f, 0f))
            add(Placement("meshes/f/furn_de_ex_bench_01.nif", -150f, -420f, 0f, 0f))
            add(Placement("meshes/f/furn_com_lantern_hook.nif", 180f, -305f, 200f, 0f, 1f, false))
            add(Placement("meshes/l/light_com_lantern_01.nif", 180f, -325f, 190f, 0f, 1f, false))
            val guard = runCatching { GuardActor.load(assets, GuardActor.WalkPath(0f, -520f, 250f, 55f), missing) }.getOrNull()
            if (guard != null) { guard.update(0f); meshes += guard.meshes }
            LoadedScene(
                meshes,
                listOf(
                    SceneLight(-190f, -312f, 180f, 330f, colored = false),
                    SceneLight(180f, -325f, 190f, 260f, colored = true)
                ),
                floatArrayOf(0.09f, 0.11f, 0.19f), floatArrayOf(0.03f, 0.04f, 0.09f), 0.0005f,
                floatArrayOf(0f, -330f, 120f), 0.4f, 0.12f, 610f, missing, guard
            )
        }

        PreviewScene.DESK_CANDLE -> {
            meshes += quadMesh(assets, Quad("tx_imp_floor_01.dds", -500f, -400f, 0f, 500f, -400f, 0f, 500f, 400f, 0f, -500f, 400f, 0f, 128f, ground = true))
            meshes += quadMesh(assets, Quad("tx_stucco_imp_02.dds", -500f, 300f, 0f, 500f, 300f, 0f, 500f, 300f, 400f, -500f, 300f, 400f, 128f))
            add(Placement("meshes/f/furn_de_table_01.nif", 0f, 170f, 0f))
            add(Placement("meshes/f/furn_de_chair_01.nif", 10f, 60f, 0f, 180f))
            add(Placement("meshes/f/furn_de_shelf_01.nif", -300f, 285f, 150f, 0f, 1f, false))
            add(Placement("meshes/l/light_com_candle_03.nif", 10f, 170f, 57f))
            add(Placement("meshes/l/light_com_candle_03.nif", -330f, 280f, 190f))
            add(Placement("meshes/m/misc_com_bottle_01.nif", -40f, 150f, 57f))
            add(Placement("meshes/m/misc_com_bottle_04.nif", 45f, 190f, 57f))
            add(Placement("meshes/m/misc_de_pitcher_01.nif", -300f, 285f, 150f + 34f))
            add(Placement("meshes/f/furn_halfbarrel00.nif", 330f, 230f, 0f))
            LoadedScene(
                meshes,
                listOf(
                    SceneLight(10f, 170f, 100f, 300f, colored = false),
                    SceneLight(-330f, 280f, 235f, 220f, colored = true)
                ),
                floatArrayOf(0.035f, 0.03f, 0.04f), floatArrayOf(0.01f, 0.01f, 0.015f), 0.0004f,
                floatArrayOf(0f, 170f, 70f), 0.15f, 0.35f, 330f, missing
            )
        }
        PreviewScene.DWEMER_WORKSHOP -> {
            room(assets, meshes, "tx_dwrv_floor00.dds", "tx_dwrv_wall10.dds", 520f, 420f, 256f)
            add(Placement("meshes/f/furn_dwrv_table00.nif", 0f, 140f, 0f))
            add(Placement("meshes/f/furn_dwrv_stool00.nif", -30f, 40f, 0f, 20f))
            add(Placement("meshes/f/furn_dwrv_bench00.nif", -250f, 275f, 0f))
            add(Placement("meshes/f/furn_dwrv_bookshelf00.nif", 240f, 265f, 0f))
            add(Placement("meshes/f/furn_dwrv_stove00.nif", -430f, 250f, 0f, 90f))
            add(Placement("meshes/l/light_dwrv_neon00.nif", 0f, 288f, 215f, 0f, 1f, false))
            add(Placement("meshes/m/misc_com_bottle_04.nif", -35f, 140f, 57f))
            add(Placement("meshes/m/misc_com_wood_bowl_01.nif", 30f, 160f, 57f))
            add(Placement("meshes/l/light_dae_brazier00.nif", 360f, 80f, 0f))
            LoadedScene(
                meshes,
                listOf(SceneLight(0f, 262f, 215f, 360f, colored = false), SceneLight(360f, 80f, 105f, 300f, colored = true)),
                floatArrayOf(0.035f, 0.04f, 0.05f), floatArrayOf(0.01f, 0.012f, 0.016f), 0.0004f,
                floatArrayOf(60f, 150f, 110f), 0.25f, 0.28f, 560f, missing
            )
        }

        PreviewScene.CAMPFIRE -> {
            meshes += quadMesh(assets, Quad("tx_ac_dirtroad_01.dds", -1400f, -1400f, 0f, 1400f, -1400f, 0f, 1400f, 1400f, 0f, -1400f, 1400f, 0f, 256f, ground = true))
            add(Placement("meshes/f/furn_firepit00.nif", 0f, 0f, 0f))
            add(Placement("meshes/f/furn_log_01.nif", 150f, -20f, 0f, 70f, 2.4f))
            add(Placement("meshes/f/furn_log_01.nif", -140f, 60f, 0f, 20f, 2.4f))
            add(Placement("meshes/f/furn_log_01.nif", 20f, -150f, 0f, 100f, 2.4f))
            add(Placement("meshes/f/furn_bedmat_01.nif", -230f, -150f, 0f, 35f, 1.3f))
            add(Placement("meshes/f/furn_halfbarrel00.nif", 190f, -190f, 0f))
            add(Placement("meshes/f/furn_crate_open_04.nif", 260f, 90f, 0f, 15f))
            add(Placement("meshes/l/light_ashl_lantern_01.nif", 260f, 90f, 72f))
            add(Placement("meshes/x/ex_nord_rock_01.nif", -300f, 200f, 0f, 30f, 2.5f))
            add(Placement("meshes/x/ex_nord_rock_01.nif", -380f, 120f, 0f, 80f, 1.8f))
            add(Placement("meshes/x/ex_nord_rock_01.nif", 330f, -60f, 0f, 10f, 2f))
            LoadedScene(
                meshes,
                listOf(SceneLight(0f, 0f, 80f, 420f, colored = false, fire = true, flameSize = 7f), SceneLight(260f, 90f, 100f, 240f, colored = true)),
                floatArrayOf(0.08f, 0.1f, 0.17f), floatArrayOf(0.03f, 0.04f, 0.08f), 0.0006f,
                floatArrayOf(40f, 20f, 50f), 0.35f, 0.3f, 560f, missing
            )
        }

        PreviewScene.WINE_CELLAR -> {
            room(assets, meshes, "tx_common_stone_floor.dds", "tx_imp_wall_01.dds", 520f, 400f, 256f)
            add(Placement("meshes/f/furn_com_kegstand.nif", -200f, 235f, 0f))
            add(Placement("meshes/f/furn_com_kegstand.nif", 20f, 235f, 0f))
            add(Placement("meshes/f/furn_winekeg00.nif", 250f, 215f, 0f, 200f))
            add(Placement("meshes/f/furn_com_winerack.nif", -400f, 280f, 0f))
            add(Placement("meshes/f/furn_crate_open_01.nif", 160f, 90f, 0f, 15f))
            add(Placement("meshes/f/furn_halfbarrel00.nif", -120f, 90f, 0f))
            add(Placement("meshes/l/light_com_lantern_01.nif", 20f, 235f, 124f))
            add(Placement("meshes/l/light_de_lantern_01.nif", 160f, 90f, 57f))
            LoadedScene(
                meshes,
                listOf(SceneLight(20f, 235f, 160f, 340f, colored = false), SceneLight(160f, 90f, 90f, 240f, colored = true)),
                floatArrayOf(0.03f, 0.03f, 0.04f), floatArrayOf(0.01f, 0.01f, 0.015f), 0.0004f,
                floatArrayOf(0f, 170f, 70f), 0.1f, 0.3f, 520f, missing
            )
        }

        PreviewScene.BEDROOM -> {
            room(assets, meshes, "tx_imp_floor_01.dds", "tx_stucco_imp_02.dds", 480f, 380f, 256f)
            add(Placement("meshes/f/furn_com_bed_03.nif", -90f, 235f, 0f))
            add(Placement("meshes/f/furn_bed_rug_01.nif", 120f, 130f, 0f, 90f))
            add(Placement("meshes/f/furn_de_table_03.nif", 120f, 265f, 0f))
            add(Placement("meshes/l/light_com_candle_03.nif", 120f, 265f, 70f))
            add(Placement("meshes/m/misc_com_bottle_01.nif", 70f, 262f, 70f))
            add(Placement("meshes/f/furn_de_shelf_01.nif", 380f, 290f, 140f, 0f, 1f, false))
            add(Placement("meshes/l/light_com_candle_03.nif", 380f, 285f, 174f))
            add(Placement("meshes/f/furn_de_chair_01.nif", -10f, 130f, 0f, 120f))
            LoadedScene(
                meshes,
                listOf(SceneLight(120f, 265f, 118f, 330f, colored = false), SceneLight(380f, 285f, 215f, 230f, colored = true)),
                floatArrayOf(0.035f, 0.032f, 0.045f), floatArrayOf(0.01f, 0.01f, 0.015f), 0.0004f,
                floatArrayOf(60f, 200f, 70f), 0.2f, 0.3f, 480f, missing
            )
        }

        PreviewScene.ALCHEMIST -> {
            room(assets, meshes, "tx_imp_floor_02.dds", "tx_stucco_imp_01.dds", 480f, 380f, 256f)
            add(Placement("meshes/f/furn_com_table_01.nif", -130f, 235f, 0f))
            add(Placement("meshes/m/misc_mortarpestle_01.nif", -170f, 225f, 68f))
            add(Placement("meshes/m/misc_potion_standard_01.nif", -110f, 250f, 68f))
            add(Placement("meshes/m/misc_potion_fresh_01.nif", -80f, 225f, 68f))
            add(Placement("meshes/l/light_com_candle_03.nif", -135f, 255f, 68f))
            add(Placement("meshes/f/furn_com_cauldron_01.nif", 110f, 160f, 0f))
            add(Placement("meshes/f/furn_de_bookshelf_01.nif", 330f, 290f, 0f))
            add(Placement("meshes/f/furn_de_shelf_01.nif", -380f, 290f, 160f, 0f, 1f, false))
            add(Placement("meshes/l/light_dae_brazier00.nif", 300f, 60f, 0f))
            LoadedScene(
                meshes,
                listOf(SceneLight(-135f, 255f, 115f, 330f, colored = false), SceneLight(300f, 60f, 105f, 300f, colored = true)),
                floatArrayOf(0.03f, 0.035f, 0.04f), floatArrayOf(0.01f, 0.01f, 0.015f), 0.0004f,
                floatArrayOf(60f, 170f, 80f), 0.22f, 0.3f, 540f, missing
            )
        }
    }
}
