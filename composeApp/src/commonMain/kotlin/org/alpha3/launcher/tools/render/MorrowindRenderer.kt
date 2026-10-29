package org.alpha3.launcher.tools.render

import org.alpha3.launcher.utils.availableCpuCores
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small multi-threaded software rasterizer: perspective-correct texturing with mip selection and
 * per-pixel point lighting. It exists so the light settings can be previewed on real Morrowind
 * objects, using the same kind of falloff the game applies to its lights.
 */

/** A mesh placed in world space, ready to draw. */
internal class SceneMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray?,
    val colors: FloatArray?,
    val indices: IntArray,
    val texture: Bitmap?,
    val diffuse: FloatArray,
    val emissive: FloatArray,
    val alphaBlend: Boolean,
    val alphaTest: Boolean,
    val isGround: Boolean = false
) {
    val vertexCount = positions.size / 3
}

internal class SceneLight(
    val x: Float, val y: Float, val z: Float,
    val baseRadius: Float,
    /** true for the "colored" light settings, false for the "standard" light settings. */
    val colored: Boolean,
    val flameHeight: Float = 10f,
    /** A real fire: fixed warm color, animated flame tongues and embers, not driven by the HSV settings. */
    val fire: Boolean = false,
    /** Flame size relative to a torch (1 = torch, ~5 = campfire). */
    val flameSize: Float = 1f
)

/** An upright capsule that shades the ground from the lights: a standing figure, base at (x, y) on the floor. */
internal class ShadowCaster(val x: Float, val y: Float, val height: Float, val radius: Float)

internal class RenderLight(
    val x: Float, val y: Float, val z: Float, val r: Float, val g: Float, val b: Float, val radius: Float,
    val fire: Boolean = false, val flameSize: Float = 1f
)

internal class RenderParams(
    val time: Float,
    val camYaw: Float,
    val camPitch: Float,
    val camDist: Float,
    val camTarget: FloatArray,
    val lights: List<RenderLight>,
    val ambient: FloatArray,
    val fog: FloatArray,
    val fogDensity: Float,
    val rain: Boolean,
    val puddles: Boolean,
    /** 0..1: how hard it is raining (drop count, brightness and ripple density). */
    val rainAmount: Float = 0.5f,
    /** Things that block light from reaching the ground, with soft edges. */
    val casters: List<ShadowCaster> = emptyList()
)

internal class MwRenderer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)

    /** Leave pixels no geometry touched fully transparent (ARGB output) instead of filling them with the fog color. */
    var transparent = false
    private val zbuf = FloatArray(width * height)

    private val vs = 14
    private var tri = FloatArray(42 * 4096)
    private var triMesh = IntArray(4096)
    private var triCount = 0

    private var camX = 0f; private var camY = 0f; private var camZ = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var rx = 0f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 0f; private var uz = 0f
    private val focal = (height / 2f) / tan((30.0 * PI / 180.0)).toFloat()

    private val bands = availableCpuCores().coerceIn(2, 8)

    fun render(meshes: List<SceneMesh>, p: RenderParams) {
        setupCamera(p)
        triCount = 0
        for ((i, m) in meshes.withIndex()) project(i, m)

        val bg = packColor(p.fog[0] * 0.6f, p.fog[1] * 0.6f, p.fog[2] * 0.6f)
        pixels.fill(if (transparent) 0 else bg)
        zbuf.fill(0f)

        // Each thread takes every bands-th row rather than a block of rows: the model sits in the middle of the view, so
        // blocks would leave the top and bottom threads idle while the middle ones did the work. Opaque first, then
        // blended geometry (rows are independent, so each thread can do both passes in order).
        parallelFor(bands) { b ->
            for (pass in 0..1) {
                for (t in 0 until triCount) {
                    val m = meshes[triMesh[t]]
                    if ((pass == 1) != m.alphaBlend) continue
                    rasterize(t, m, b, p)
                }
            }
        }
        if (!transparent) {
            postGlow(p)
            if (p.rain) drawRain(p)
        }
    }

    private fun setupCamera(p: RenderParams) {
        val cp = cos(p.camPitch); val sp = sin(p.camPitch)
        val cy = cos(p.camYaw); val sy = sin(p.camYaw)
        camX = p.camTarget[0] + p.camDist * cp * sy
        camY = p.camTarget[1] - p.camDist * cp * cy
        camZ = p.camTarget[2] + p.camDist * sp
        fx = p.camTarget[0] - camX; fy = p.camTarget[1] - camY; fz = p.camTarget[2] - camZ
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        // right = forward x worldUp(0,0,1)
        rx = fy; ry = -fx; rz = 0f
        val rl = sqrt(rx * rx + ry * ry)
        rx /= rl; ry /= rl
        // up = right x forward
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx
    }

    private fun ensureTris(extra: Int) {
        if ((triCount + extra) * 42 > tri.size) {
            tri = tri.copyOf(tri.size * 2)
            triMesh = triMesh.copyOf(triMesh.size * 2)
        }
    }

    private val rawA = FloatArray(14); private val rawB = FloatArray(14); private val rawC = FloatArray(14)
    private val poly = Array(6) { FloatArray(14) }
    private val polyOut = Array(6) { FloatArray(14) }

    private fun fill(dst: FloatArray, m: SceneMesh, v: Int) {
        val px = m.positions[v * 3]; val py = m.positions[v * 3 + 1]; val pz = m.positions[v * 3 + 2]
        val dx = px - camX; val dy = py - camY; val dz = pz - camZ
        dst[0] = dx * rx + dy * ry + dz * rz
        dst[1] = dx * ux + dy * uy + dz * uz
        dst[2] = dx * fx + dy * fy + dz * fz
        dst[3] = m.uvs?.get(v * 2) ?: 0f
        dst[4] = m.uvs?.get(v * 2 + 1) ?: 0f
        dst[5] = px; dst[6] = py; dst[7] = pz
        dst[8] = m.normals[v * 3]; dst[9] = m.normals[v * 3 + 1]; dst[10] = m.normals[v * 3 + 2]
        val c = m.colors
        if (c != null) { dst[11] = c[v * 4]; dst[12] = c[v * 4 + 1]; dst[13] = c[v * 4 + 2] }
        else { dst[11] = 1f; dst[12] = 1f; dst[13] = 1f }
    }

    private fun project(meshIndex: Int, m: SceneMesh) {
        val near = 4f
        var i = 0
        while (i + 2 < m.indices.size) {
            fill(rawA, m, m.indices[i]); fill(rawB, m, m.indices[i + 1]); fill(rawC, m, m.indices[i + 2])
            i += 3
            if (rawA[2] < near && rawB[2] < near && rawC[2] < near) continue
            // quick frustum reject on the right/left/top/bottom planes
            val limX = 1.4f; val limY = 1.4f
            if (rawA[0] > rawA[2] * limX && rawB[0] > rawB[2] * limX && rawC[0] > rawC[2] * limX) continue
            if (rawA[0] < -rawA[2] * limX && rawB[0] < -rawB[2] * limX && rawC[0] < -rawC[2] * limX) continue
            if (rawA[1] > rawA[2] * limY && rawB[1] > rawB[2] * limY && rawC[1] > rawC[2] * limY) continue
            if (rawA[1] < -rawA[2] * limY && rawB[1] < -rawB[2] * limY && rawC[1] < -rawC[2] * limY) continue

            if (rawA[2] >= near && rawB[2] >= near && rawC[2] >= near) {
                ensureTris(1)
                emit(rawA, rawB, rawC, meshIndex)
            } else {
                rawA.copyInto(poly[0], 0, 0, 14)
                rawB.copyInto(poly[1], 0, 0, 14)
                rawC.copyInto(poly[2], 0, 0, 14)
                var n = 3
                var outN = 0
                for (k in 0 until n) {
                    val a = poly[k]; val b = poly[(k + 1) % n]
                    val ain = a[2] >= near; val bin = b[2] >= near
                    if (ain) { a.copyInto(polyOut[outN++], 0, 0, 14) }
                    if (ain != bin) {
                        val t = (near - a[2]) / (b[2] - a[2])
                        val o = polyOut[outN++]
                        for (c in 0 until 14) o[c] = a[c] + (b[c] - a[c]) * t
                    }
                }
                n = outN
                if (n >= 3) {
                    ensureTris(n - 2)
                    for (k in 1 until n - 1) emit(polyOut[0], polyOut[k], polyOut[k + 1], meshIndex)
                }
            }
        }
    }

    private fun emit(a: FloatArray, b: FloatArray, c: FloatArray, meshIndex: Int) {
        val base = triCount * 42
        writeVertex(base, a); writeVertex(base + 14, b); writeVertex(base + 28, c)
        triMesh[triCount++] = meshIndex
    }

    private fun writeVertex(o: Int, r: FloatArray) {
        val iw = 1f / r[2]
        tri[o] = width / 2f + r[0] * iw * focal
        tri[o + 1] = height / 2f - r[1] * iw * focal
        tri[o + 2] = iw
        for (k in 3 until 14) tri[o + k] = r[k] * iw
    }

    /** Draws the rows of triangle [t] that belong to thread [band] (every bands-th row, starting at row [band]). */
    private fun rasterize(t: Int, m: SceneMesh, band: Int, p: RenderParams) {
        val o = t * 42
        val x0 = tri[o]; val ya = tri[o + 1]
        val x1 = tri[o + 14]; val yb = tri[o + 15]
        val x2 = tri[o + 28]; val yc = tri[o + 29]
        val topY = max(0, floor(min(ya, min(yb, yc))).toInt())
        val maxY = min(height - 1, floor(max(ya, max(yb, yc))).toInt())
        // the first row at or below topY that is this thread's
        val minY = topY + ((band - topY % bands) + bands) % bands
        if (minY > maxY) return
        val minX = max(0, floor(min(x0, min(x1, x2))).toInt())
        val maxX = min(width - 1, floor(max(x0, max(x1, x2))).toInt())
        if (minX > maxX) return
        val d = (yb - yc) * (x0 - x2) + (x2 - x1) * (ya - yc)
        if (abs(d) < 1e-8f) return
        val inv = 1f / d
        val l0x = (yb - yc) * inv; val l0y = (x2 - x1) * inv
        val l1x = (yc - ya) * inv; val l1y = (x0 - x2) * inv
        val l2x = -l0x - l1x; val l2y = -l0y - l1y

        val iw0 = tri[o + 2]; val iw1 = tri[o + 16]; val iw2 = tri[o + 30]
        val gWx = l0x * iw0 + l1x * iw1 + l2x * iw2
        val gWy = l0y * iw0 + l1y * iw1 + l2y * iw2
        val gUx = l0x * tri[o + 3] + l1x * tri[o + 17] + l2x * tri[o + 31]
        val gUy = l0y * tri[o + 3] + l1y * tri[o + 17] + l2y * tri[o + 31]
        val gVx = l0x * tri[o + 4] + l1x * tri[o + 18] + l2x * tri[o + 32]
        val gVy = l0y * tri[o + 4] + l1y * tri[o + 18] + l2y * tri[o + 32]

        val tex = m.texture
        val texW = tex?.width ?: 1
        val texH = tex?.height ?: 1
        val lights = p.lights
        val amb = p.ambient

        for (py in minY..maxY step bands) {
            val cyp = py + 0.5f
            val cxp0 = minX + 0.5f
            var l0 = ((yb - yc) * (cxp0 - x2) + (x2 - x1) * (cyp - yc)) * inv
            var l1 = ((yc - ya) * (cxp0 - x2) + (x0 - x2) * (cyp - yc)) * inv
            var l2 = 1f - l0 - l1
            for (px in minX..maxX) {
                if (l0 >= -1e-4f && l1 >= -1e-4f && l2 >= -1e-4f) {
                    val idx = py * width + px
                    val w = l0 * iw0 + l1 * iw1 + l2 * iw2
                    if (w > zbuf[idx] || m.alphaBlend && w > 0f && w >= zbuf[idx] * 0.9999f) {
                        val iwInv = 1f / w
                        val u = (l0 * tri[o + 3] + l1 * tri[o + 17] + l2 * tri[o + 31]) * iwInv
                        val v = (l0 * tri[o + 4] + l1 * tri[o + 18] + l2 * tri[o + 32]) * iwInv

                        var tr = 1f; var tg = 1f; var tb = 1f; var ta = 1f
                        if (tex != null) {
                            val dudx = (gUx - u * gWx) * iwInv; val dudy = (gUy - u * gWy) * iwInv
                            val dvdx = (gVx - v * gWx) * iwInv; val dvdy = (gVy - v * gWy) * iwInv
                            val rx2 = (dudx * dudx + dvdx * dvdx) * texW * texH
                            val ry2 = (dudy * dudy + dvdy * dvdy) * texW * texH
                            val rho2 = max(rx2, ry2)
                            val lod = if (rho2 > 1f) (0.5f * ln(rho2) / LN2 + 0.2f).toInt() else 0
                            val level = tex.levels[min(lod, tex.levels.size - 1)]
                            val s = sample(level.first, level.second, level.third, u, v)
                            ta = ((s ushr 24) and 255) / 255f
                            tr = ((s shr 16) and 255) / 255f; tg = ((s shr 8) and 255) / 255f; tb = (s and 255) / 255f
                            if (m.alphaTest && ta < 0.5f) { l0 += l0x; l1 += l1x; l2 += l2x; continue }
                        }

                        val wx = (l0 * tri[o + 5] + l1 * tri[o + 19] + l2 * tri[o + 33]) * iwInv
                        val wy = (l0 * tri[o + 6] + l1 * tri[o + 20] + l2 * tri[o + 34]) * iwInv
                        val wz = (l0 * tri[o + 7] + l1 * tri[o + 21] + l2 * tri[o + 35]) * iwInv
                        var nx = (l0 * tri[o + 8] + l1 * tri[o + 22] + l2 * tri[o + 36]) * iwInv
                        var ny = (l0 * tri[o + 9] + l1 * tri[o + 23] + l2 * tri[o + 37]) * iwInv
                        var nz = (l0 * tri[o + 10] + l1 * tri[o + 24] + l2 * tri[o + 38]) * iwInv
                        val cr = (l0 * tri[o + 11] + l1 * tri[o + 25] + l2 * tri[o + 39]) * iwInv
                        val cg = (l0 * tri[o + 12] + l1 * tri[o + 26] + l2 * tri[o + 40]) * iwInv
                        val cb = (l0 * tri[o + 13] + l1 * tri[o + 27] + l2 * tri[o + 41]) * iwInv

                        var nl = sqrt(nx * nx + ny * ny + nz * nz)
                        if (nl < 1e-5f) { nx = 0f; ny = 0f; nz = 1f; nl = 1f }
                        nx /= nl; ny /= nl; nz /= nl

                        // view vector
                        var vx = camX - wx; var vy = camY - wy; var vz = camZ - wz
                        val vl = sqrt(vx * vx + vy * vy + vz * vz)
                        vx /= vl; vy /= vl; vz /= vl

                        var albedoScale = 1f
                        var specular = 0f
                        if (m.isGround && p.puddles) {
                            val wet = wetness(wx, wy)
                            albedoScale = 1f - 0.5f * wet
                            specular = wet
                            if (p.rain) {
                                val rip = ripple(wx, wy, p.time, p.rainAmount)
                                nx += rip[0] * wet; ny += rip[1] * wet
                                val l = sqrt(nx * nx + ny * ny + nz * nz)
                                nx /= l; ny /= l; nz /= l
                            }
                        }
                        if (nx * vx + ny * vy + nz * vz < 0f) { nx = -nx; ny = -ny; nz = -nz }

                        var lr = amb[0]; var lg = amb[1]; var lb = amb[2]
                        var sr = 0f; var sg = 0f; var sb = 0f
                        for (li in lights.indices) {
                            val L = lights[li]
                            var dx = L.x - wx; var dy = L.y - wy; var dz = L.z - wz
                            val d2 = dx * dx + dy * dy + dz * dz
                            val r2 = L.radius * L.radius
                            if (d2 >= r2) continue
                            val dist = sqrt(d2)
                            dx /= dist; dy /= dist; dz /= dist
                            val ndl = nx * dx + ny * dy + nz * dz
                            val wrap = max(0f, (ndl + 0.25f) / 1.25f)
                            val f = 1f - d2 / r2
                            val att = f * f
                            var k = wrap * att
                            if (m.isGround && p.casters.isNotEmpty()) k *= groundLightVisibility(wx, wy, wz, L, p.casters)
                            lr += L.r * k; lg += L.g * k; lb += L.b * k
                            if (specular > 0f) {
                                var hx = dx + vx; var hy = dy + vy; var hz = dz + vz
                                val hl = sqrt(hx * hx + hy * hy + hz * hz)
                                if (hl > 1e-5f) {
                                    hx /= hl; hy /= hl; hz /= hl
                                    val nh = nx * hx + ny * hy + nz * hz
                                    if (nh > 0f) {
                                        val sp = nh.pow(48f) * att * specular * 1.6f
                                        sr += L.r * sp; sg += L.g * sp; sb += L.b * sp
                                    }
                                }
                            }
                        }

                        val dr = m.diffuse[0] * cr * albedoScale; val dg = m.diffuse[1] * cg * albedoScale; val db = m.diffuse[2] * cb * albedoScale
                        var outR = tr * dr * lr + m.emissive[0] + sr
                        var outG = tg * dg * lg + m.emissive[1] + sg
                        var outB = tb * db * lb + m.emissive[2] + sb

                        if (p.fogDensity > 0f) {
                            val dist = iwInv
                            val fa = 1f - exp(-dist * p.fogDensity)
                            outR += (p.fog[0] - outR) * fa
                            outG += (p.fog[1] - outG) * fa
                            outB += (p.fog[2] - outB) * fa
                        }

                        if (m.alphaBlend) {
                            val a = (ta * 1f).coerceIn(0f, 1f)
                            val dst = pixels[idx]
                            val br = ((dst shr 16) and 255) / 255f; val bg = ((dst shr 8) and 255) / 255f; val bb = (dst and 255) / 255f
                            if (transparent) {
                                // composite over whatever is already there, tracking coverage in the alpha channel
                                val da = ((dst ushr 24) and 255) / 255f
                                val na = a + da * (1 - a)
                                if (na > 1e-4f) {
                                    val k = da * (1 - a)
                                    val ai = (na * 255f).toInt().coerceIn(0, 255)
                                    pixels[idx] = (packColor((outR * a + br * k) / na, (outG * a + bg * k) / na, (outB * a + bb * k) / na) and 0xFFFFFF) or (ai shl 24)
                                }
                            } else {
                                pixels[idx] = packColor(outR * a + br * (1 - a), outG * a + bg * (1 - a), outB * a + bb * (1 - a))
                            }
                        } else {
                            pixels[idx] = packColor(outR, outG, outB)
                            zbuf[idx] = w
                        }
                    }
                }
                l0 += l0x; l1 += l1x; l2 += l2x
            }
        }
    }

    /**
     * 1 = the light reaches the point, 0 = a caster is squarely in the way. The edge is soft: it fades over
     * the outer half of the caster's width, which also stands in for the light's size.
     */
    private fun groundLightVisibility(px: Float, py: Float, pz: Float, light: RenderLight, casters: List<ShadowCaster>): Float {
        val dx = light.x - px; val dy = light.y - py; val dz = light.z - pz
        val len2 = dx * dx + dy * dy + dz * dz
        if (len2 < 1e-4f) return 1f
        var vis = 1f
        for (c in casters) {
            var best = Float.MAX_VALUE
            for (s in 0..4) {
                val cz = c.height * s / 4f
                val ex = c.x - px; val ey = c.y - py; val ez = cz - pz
                var t = (ex * dx + ey * dy + ez * dz) / len2
                if (t < 0f || t > 1f) { if (t < 0f) t = 0f else t = 1f }
                // a sample that is past the light (or behind the point) can't block it
                val qx = px + dx * t - c.x; val qy = py + dy * t - c.y; val qz = pz + dz * t - cz
                val d2 = qx * qx + qy * qy + qz * qz
                if (d2 < best) best = d2
            }
            val d = sqrt(best)
            val inner = c.radius * 0.6f
            val outer = c.radius * 1.6f
            val shade = if (d <= inner) 1f else if (d >= outer) 0f else { val f = (d - inner) / (outer - inner); 1f - f * f * (3f - 2f * f) }
            vis *= 1f - 0.85f * shade
        }
        return vis
    }

    private fun sample(w: Int, h: Int, data: IntArray, u: Float, v: Float): Int {
        val fu = u * w - 0.5f
        val fv = v * h - 0.5f
        val ix = floor(fu).toInt(); val iy = floor(fv).toInt()
        val ax = fu - ix; val ay = fv - iy
        val x0 = if (w and (w - 1) == 0) ix and (w - 1) else (ix).mod(w)
        val y0 = if (h and (h - 1) == 0) iy and (h - 1) else (iy).mod(h)
        val x1 = if (w and (w - 1) == 0) (ix + 1) and (w - 1) else (ix + 1).mod(w)
        val y1 = if (h and (h - 1) == 0) (iy + 1) and (h - 1) else (iy + 1).mod(h)
        val c00 = data[y0 * w + x0]; val c10 = data[y0 * w + x1]
        val c01 = data[y1 * w + x0]; val c11 = data[y1 * w + x1]
        var out = 0
        var s = 24
        while (s >= 0) {
            val a = (c00 ushr s) and 255; val b = (c10 ushr s) and 255
            val c = (c01 ushr s) and 255; val d = (c11 ushr s) and 255
            val top = a + (b - a) * ax
            val bot = c + (d - c) * ax
            out = out or ((top + (bot - top) * ay).toInt().coerceIn(0, 255) shl s)
            s -= 8
        }
        return out
    }

    // --- wet ground -----------------------------------------------------------------------

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy); val b = hash(ix + 1, iy); val c = hash(ix, iy + 1); val d = hash(ix + 1, iy + 1)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun wetness(x: Float, y: Float): Float {
        val n = valueNoise(x / 70f, y / 70f) * 0.65f + valueNoise(x / 22f, y / 22f) * 0.35f
        return ((n - 0.35f) * 3.2f).coerceIn(0f, 1f)
    }

    private val rip = FloatArray(2)
    private fun ripple(x: Float, y: Float, t: Float, amount: Float): FloatArray {
        rip[0] = 0f; rip[1] = 0f
        val cell = 36f
        val cx = floor(x / cell).toInt(); val cy = floor(y / cell).toInt()
        if (hash(cx + 31, cy + 17) > amount) return rip
        val ox = (hash(cx, cy) * 0.4f + 0.3f) * cell + cx * cell
        val oy = (hash(cy, cx + 91) * 0.4f + 0.3f) * cell + cy * cell
        val period = 1.3f
        val phase = ((t * 0.9f + hash(cx + 7, cy + 3) * 5f) % period) / period
        val dx = x - ox; val dy = y - oy
        val r = sqrt(dx * dx + dy * dy)
        val ringR = phase * 15f
        val dr = r - ringR
        if (abs(dr) < 3f && r > 0.01f) {
            val amp = (1f - phase) * cos(dr / 3f * (PI.toFloat() / 2f)) * 0.5f
            val s = sin(dr * 2.1f) * amp
            rip[0] = dx / r * s; rip[1] = dy / r * s
        }
        return rip
    }

    // --- screen-space post effects --------------------------------------------------------

    private fun project3(wx: Float, wy: Float, wz: Float, out: FloatArray): Boolean {
        val dx = wx - camX; val dy = wy - camY; val dz = wz - camZ
        val cz = dx * fx + dy * fy + dz * fz
        if (cz < 4f) return false
        out[0] = width / 2f + (dx * rx + dy * ry + dz * rz) / cz * focal
        out[1] = height / 2f - (dx * ux + dy * uy + dz * uz) / cz * focal
        out[2] = cz
        return true
    }

    /** Flame sprites and a soft halo around every light; hidden if the light is behind geometry. */
    private fun postGlow(p: RenderParams) {
        val s = FloatArray(3)
        for (L in p.lights) {
            if (!project3(L.x, L.y, L.z, s)) continue
            val sx = s[0]; val sy = s[1]
            val ix = sx.toInt(); val iy = sy.toInt()
            val visible = ix in 0 until width && iy in 0 until height && (1f / s[2]) >= zbuf[iy * width + ix] * 0.97f
            val scale = focal / s[2]
            val intensity = min(1.6f, (L.r + L.g + L.b) / 3f * 1.2f + 0.15f)

            val haloR = L.radius * 0.22f * scale
            if (visible && L.fire) {
                additiveBlob(sx, sy, haloR, haloR, 0.30f * intensity, L.r, L.g, L.b, 2.2f)
                drawFire(sx, sy, scale * L.flameSize, p.time, L)
            } else if (visible) {
                additiveBlob(sx, sy, haloR, haloR, 0.35f * intensity, L.r, L.g, L.b, 2.2f)
                // flame: a taller blob, white-hot core tinted by the light colour
                val fw = 3.8f * scale; val fh = 9f * scale
                additiveBlob(sx, sy - fh * 0.15f, fw * 1.7f, fh * 1.7f, 0.9f, L.r, L.g, L.b, 2.6f)
                val mx = max(L.r, max(L.g, L.b)).coerceAtLeast(0.01f)
                additiveBlob(sx, sy - fh * 0.05f, fw * 0.8f, fh * 0.8f, 1.3f, 0.5f + L.r / mx * 0.5f, 0.5f + L.g / mx * 0.5f, 0.5f + L.b / mx * 0.5f, 3.2f)
            } else if (ix in 0 until width && iy in 0 until height) {
                // partly hidden: just a faint bleed so the light still reads as being behind the object
                additiveBlob(sx, sy, haloR * 0.5f, haloR * 0.5f, 0.08f * intensity, L.r, L.g, L.b, 2.2f)
            }
        }
    }

    /** Animated flame: a few tongues that sway and change height, hot white-yellow at the base, plus embers. */
    private fun drawFire(sx: Float, sy: Float, scale: Float, time: Float, L: RenderLight) {
        val tongues = 6
        for (i in 0 until tongues) {
            val ph = i * 1.93f
            val spread = (i - (tongues - 1) / 2f) / tongues
            val flick = 0.75f + 0.25f * sin(time * (7f + i * 1.3f) + ph) + 0.15f * sin(time * (13f + i * 2.1f) + ph * 2f)
            val h = 9f * scale * flick * (1f - 0.45f * kotlin.math.abs(spread) * 2f)
            val w = 3.6f * scale * (1f - 0.3f * kotlin.math.abs(spread) * 2f)
            val bx = sx + spread * 9f * scale
            // outer red-orange body, then yellow, then a white-hot core near the base
            for (layer in 0..2) {
                val f = 1f - layer * 0.3f
                val cy = sy - h * (0.35f + 0.1f * layer) * 0.9f
                val sway = sin(time * (5f + i) + ph + layer * 0.7f) * w * 0.35f * (1 - f + 0.5f)
                val r = if (layer == 0) 1.0f else if (layer == 1) 1.0f else 1.0f
                val g = if (layer == 0) 0.32f else if (layer == 1) 0.72f else 0.95f
                val b = if (layer == 0) 0.04f else if (layer == 1) 0.18f else 0.65f
                additiveBlob(bx + sway, cy, w * f, h * 0.7f * f, if (layer == 2) 0.9f else 0.55f, r, g, b, 2.4f)
            }
        }
        // embers drifting up
        for (e in 0 until 10) {
            val seed = e * 5.37f
            val life = ((time * (0.5f + (e % 3) * 0.2f) + seed) % 1f)
            val ex = sx + sin(seed * 3f + life * 6f) * 10f * scale * (0.4f + life)
            val ey = sy - life * 34f * scale
            val a = (1f - life) * 0.9f
            additiveBlob(ex, ey, 1.2f + scale * 0.25f, 1.2f + scale * 0.25f, a, 1f, 0.55f, 0.15f, 1.5f)
        }
    }

    private fun additiveBlob(cx: Float, cy: Float, rxp: Float, ryp: Float, strength: Float, r: Float, g: Float, b: Float, sharp: Float) {
        if (rxp < 0.5f || ryp < 0.5f) return
        val x0 = max(0, (cx - rxp).toInt()); val x1 = min(width - 1, (cx + rxp).toInt())
        val y0 = max(0, (cy - ryp).toInt()); val y1 = min(height - 1, (cy + ryp).toInt())
        for (y in y0..y1) {
            val ny = (y - cy) / ryp
            for (x in x0..x1) {
                val nx = (x - cx) / rxp
                val q = nx * nx + ny * ny
                if (q >= 1f) continue
                val a = exp(-q * sharp * 2f) * strength * (1f - q)
                val dst = pixels[y * width + x]
                val rr = (((dst shr 16) and 255) + r * a * 255f).toInt().coerceIn(0, 255)
                val gg = (((dst shr 8) and 255) + g * a * 255f).toInt().coerceIn(0, 255)
                val bb = ((dst and 255) + b * a * 255f).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    private class Drop(val x: Float, val y: Float, val depth: Float, val speed: Float, val phase: Float, val rank: Float)
    private val drops = List(700) {
        val h = kotlin.random.Random(it * 7919L)
        // depth 0 = far, 1 = near; far drops are many, short and faint
        val depth = h.nextFloat().let { d -> d * d }
        Drop(h.nextFloat(), h.nextFloat(), depth, 0.7f + depth * 0.9f, h.nextFloat(), it / 700f)
    }

    private fun drawRain(p: RenderParams) {
        val amount = p.rainAmount.coerceIn(0f, 1f)
        if (amount <= 0.01f) return
        val s = FloatArray(3)
        val glows = p.lights.mapNotNull { L ->
            if (project3(L.x, L.y, L.z, s)) floatArrayOf(s[0], s[1], L.radius * focal / s[2] * 0.9f, L.r, L.g, L.b) else null
        }
        val slant = 0.10f
        for (d in drops) {
            if (d.rank > amount) continue
            val yy = ((d.y + p.time * d.speed * 0.8f + d.phase) % 1f)
            val xx = (d.x + yy * slant * 0.5f) % 1f
            val x0 = xx * width; val y0 = yy * height * 1.1f - height * 0.05f
            val len = height * (0.012f + d.depth * 0.05f)
            val x1 = x0 - len * slant; val y1 = y0 + len
            var br = 0.025f + d.depth * 0.07f
            var cr = 0.55f; var cg = 0.62f; var cb = 0.78f
            for (g in glows) {
                val dx = x0 - g[0]; val dy = y0 - g[1]
                val q = (dx * dx + dy * dy) / (g[2] * g[2])
                if (q < 1f) {
                    val k = (1f - q) * (1f - q) * (0.25f + d.depth * 0.5f)
                    br += k
                    cr += g[3] * k; cg += g[4] * k; cb += g[5] * k
                }
            }
            val steps = max(2, len.toInt())
            for (i in 0..steps) {
                val tt = i / steps.toFloat()
                val x = (x0 + (x1 - x0) * tt).toInt(); val y = (y0 + (y1 - y0) * tt).toInt()
                if (x !in 0 until width || y !in 0 until height) continue
                val fade = (0.3f + 0.7f * tt) * br
                val dst = pixels[y * width + x]
                val rr = (((dst shr 16) and 255) + cr * fade * 255f).toInt().coerceIn(0, 255)
                val gg = (((dst shr 8) and 255) + cg * fade * 255f).toInt().coerceIn(0, 255)
                val bb = ((dst and 255) + cb * fade * 255f).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    private fun packColor(r: Float, g: Float, b: Float): Int {
        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        return (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private companion object {
        val LN2 = ln(2f)
    }
}
