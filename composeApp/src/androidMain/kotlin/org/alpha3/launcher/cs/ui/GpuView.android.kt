package org.alpha3.launcher.cs.ui

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.alpha3.launcher.tools.render.Bitmap
import org.alpha3.launcher.tools.render.SceneMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.tan

internal actual val gpuCellViewAvailable: Boolean = true

@Composable
internal actual fun GpuCellView(frame: () -> GpuFrame?, continuous: Boolean, dirty: Any, modifier: Modifier) {
    val source = rememberUpdatedState(frame)
    val renderer = remember { CellGlRenderer { source.value() } }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 24, 0)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        },
        update = { view ->
            // reading [dirty] here makes Compose call this again whenever it changes
            dirty.hashCode()
            view.renderMode = if (continuous) GLSurfaceView.RENDERMODE_CONTINUOUSLY else GLSurfaceView.RENDERMODE_WHEN_DIRTY
            view.requestRender()
        },
        modifier = modifier,
    )
}

private const val VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aCol;
uniform mat4 uViewProj;
out vec3 vPos; out vec3 vNrm; out vec2 vUv; out vec4 vCol;
void main() {
    vPos = aPos; vNrm = aNrm; vUv = aUv; vCol = aCol;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

// The same lighting as the software renderer: ambient plus point lights with a wrapped diffuse term and a
// (1 - d^2/r^2)^2 falloff, so both views look alike.
private const val FS = """#version 300 es
precision highp float;
in vec3 vPos; in vec3 vNrm; in vec2 vUv; in vec4 vCol;
uniform sampler2D uTex;
uniform int uHasTex;
uniform int uAlphaTest;
uniform int uBlend;
uniform int uWater;
uniform vec3 uDiffuse;
uniform vec3 uEmissive;
uniform vec3 uAmbient;
uniform vec3 uEye;
uniform float uTime;
uniform int uLightCount;
uniform vec4 uLightPos[12];
uniform vec3 uLightCol[12];
out vec4 outColor;
void main() {
    vec4 t = uHasTex == 1 ? texture(uTex, vUv) : vec4(1.0);
    if (uAlphaTest == 1 && t.a < 0.5) discard;
    vec3 n = normalize(vNrm);
    vec3 v = normalize(uEye - vPos);
    vec3 base = t.rgb * uDiffuse * vCol.rgb;
    float alpha = uBlend == 1 ? t.a * vCol.a : 1.0;
    if (uWater == 1) {
        // small moving waves bend the normal; the surface is see-through and picks up a sky tint at grazing angles
        float a = vPos.x * 0.011 + uTime * 0.9, b = vPos.y * 0.013 - uTime * 0.7, c = (vPos.x + vPos.y) * 0.02 + uTime * 1.6;
        n = normalize(vec3(sin(a) * 0.09 + sin(c) * 0.05, cos(b) * 0.09 + cos(c * 1.3) * 0.04, 1.0));
        base = vec3(0.10, 0.20, 0.26);
        float fres = pow(1.0 - max(dot(n, v), 0.0), 3.0);
        base = mix(base, vec3(0.45, 0.55, 0.62), fres);
        alpha = 0.55 + 0.35 * fres;
    }
    if (dot(n, v) < 0.0) n = -n;
    vec3 light = uAmbient;
    vec3 spec = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        if (i >= uLightCount) break;
        vec3 d = uLightPos[i].xyz - vPos;
        float d2 = dot(d, d);
        float r2 = uLightPos[i].w * uLightPos[i].w;
        if (d2 >= r2) continue;
        vec3 l = d * inversesqrt(d2);
        float wrap = max(0.0, (dot(n, l) + 0.25) / 1.25);
        float f = 1.0 - d2 / r2;
        light += uLightCol[i] * wrap * f * f;
        if (uWater == 1) spec += uLightCol[i] * pow(max(dot(n, normalize(l + v)), 0.0), 64.0) * f * f;
    }
    outColor = vec4(base * light + uEmissive + spec, alpha);
}
"""

private class GlMesh(val vao: Int, val vbo: Int, val ibo: Int, val count: Int, val center: FloatArray)

/** Draws [GpuFrame]s; GL objects are made per mesh and texture the first time they are drawn and freed when unused. */
private class CellGlRenderer(private val source: () -> GpuFrame?) : GLSurfaceView.Renderer {
    private var program = 0
    private var width = 1
    private var height = 1
    private val meshes = IdentityHashMap<SceneMesh, GlMesh>()
    private val textures = IdentityHashMap<Bitmap, Int>()
    private var water: GlMesh? = null
    private var waterKey: Pair<FloatArray, Float>? = null
    private val u = HashMap<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // a new context: every GL object of the old one is gone
        meshes.clear(); textures.clear(); water = null; waterKey = null; u.clear()
        program = link(VS, FS)
        for (name in listOf("uViewProj", "uTex", "uHasTex", "uAlphaTest", "uBlend", "uWater", "uDiffuse", "uEmissive", "uAmbient", "uEye", "uTime", "uLightCount", "uLightPos", "uLightCol")) {
            u[name] = glGetUniformLocation(program, name)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val f = try { source() } catch (e: Exception) { Log.w("CellGl", "frame", e); null }
        val clear = f?.clear ?: floatArrayOf(0.05f, 0.055f, 0.07f)
        glClearColor(clear[0], clear[1], clear[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        if (f == null || program == 0) return
        val c = f.camera

        glUseProgram(program)
        glUniformMatrix4fv(u.getValue("uViewProj"), 1, false, viewProj(c), 0)
        glUniform3f(u.getValue("uEye"), c.eye[0], c.eye[1], c.eye[2])
        glUniform3f(u.getValue("uAmbient"), f.ambient[0], f.ambient[1], f.ambient[2])
        glUniform1f(u.getValue("uTime"), f.time)
        val lights = f.lights.take(12)
        glUniform1i(u.getValue("uLightCount"), lights.size)
        if (lights.isNotEmpty()) {
            val pos = FloatArray(48); val col = FloatArray(36)
            lights.forEachIndexed { i, l ->
                pos[i * 4] = l.x; pos[i * 4 + 1] = l.y; pos[i * 4 + 2] = l.z; pos[i * 4 + 3] = l.radius
                col[i * 3] = l.r; col[i * 3 + 1] = l.g; col[i * 3 + 2] = l.b
            }
            glUniform4fv(u.getValue("uLightPos"), lights.size, pos, 0)
            glUniform3fv(u.getValue("uLightCol"), lights.size, col, 0)
        }
        glUniform1i(u.getValue("uTex"), 0)
        glActiveTexture(GL_TEXTURE0)

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        // one-sided, as the game draws them: rooms can be looked into from outside
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)

        val used = java.util.Collections.newSetFromMap(IdentityHashMap<SceneMesh, Boolean>())
        val usedTex = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        val blended = ArrayList<Pair<GpuItem, GlMesh>>()
        glDisable(GL_BLEND)
        glDepthMask(true)
        for (item in f.items) {
            val m = item.mesh
            val g = meshes.getOrPut(m) { upload(m) }
            used += m
            m.texture?.let { usedTex += it }
            if (m.alphaBlend) { blended += item to g; continue }
            draw(item, g)
        }
        // see-through surfaces last, far to near, without writing depth
        if (blended.isNotEmpty()) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDepthMask(false)
            blended.sortByDescending { (_, g) -> dist2(g.center, c.eye) }
            for ((item, g) in blended) draw(item, g)
        }
        f.water?.let { h -> drawWater(h, f.waterExtent) }
        glDepthMask(true)
        glDisable(GL_BLEND)

        // free what the frame no longer uses (moved objects get new meshes while dragged)
        val stale = meshes.keys.filter { it !in used }
        for (k in stale) meshes.remove(k)?.let { g -> glDeleteVertexArrays(1, intArrayOf(g.vao), 0); glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0) }
        val staleTex = textures.keys.filter { it !in usedTex }
        for (k in staleTex) textures.remove(k)?.let { glDeleteTextures(1, intArrayOf(it), 0) }
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun draw(item: GpuItem, g: GlMesh) {
        val m = item.mesh
        val tex = m.texture?.let { textures.getOrPut(it) { uploadTexture(it) } }
        if (tex != null) glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(u.getValue("uHasTex"), if (tex != null && m.uvs != null) 1 else 0)
        glUniform1i(u.getValue("uAlphaTest"), if (m.alphaTest) 1 else 0)
        glUniform1i(u.getValue("uBlend"), if (m.alphaBlend) 1 else 0)
        glUniform1i(u.getValue("uWater"), 0)
        glUniform3f(u.getValue("uDiffuse"), m.diffuse[0], m.diffuse[1], m.diffuse[2])
        val h = if (item.highlight) 1f else 0f
        glUniform3f(u.getValue("uEmissive"), m.emissive[0] + 0.30f * h, m.emissive[1] + 0.22f * h, m.emissive[2] + 0.02f * h)
        glBindVertexArray(g.vao)
        glDrawElements(GL_TRIANGLES, g.count, GL_UNSIGNED_INT, 0)
    }

    private fun drawWater(h: Float, extent: Pair<FloatArray, Float>) {
        if (waterKey?.first?.contentEquals(extent.first) != true || waterKey?.second != extent.second || water == null) {
            water?.let { g -> glDeleteVertexArrays(1, intArrayOf(g.vao), 0); glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0) }
            val (c, r0) = extent
            val r = r0 * 3f
            // a grid, so the waves have vertices to sit on
            val n = 32
            val pos = FloatArray((n + 1) * (n + 1) * 3)
            var k = 0
            for (j in 0..n) for (i in 0..n) { pos[k++] = c[0] - r + 2 * r * i / n; pos[k++] = c[1] - r + 2 * r * j / n; pos[k++] = 0f }
            val idx = IntArray(n * n * 6)
            k = 0
            for (j in 0 until n) for (i in 0 until n) {
                val a = j * (n + 1) + i; val b = a + 1; val d = a + n + 1; val e = d + 1
                idx[k++] = a; idx[k++] = b; idx[k++] = e; idx[k++] = a; idx[k++] = e; idx[k++] = d
            }
            val nrm = FloatArray(pos.size).also { for (i in it.indices step 3) it[i + 2] = 1f }
            water = upload(SceneMesh(pos, nrm, null, null, idx, null, floatArrayOf(1f, 1f, 1f), FloatArray(3), true, false))
            waterKey = extent
        }
        val g = water ?: return
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        glUniform1i(u.getValue("uHasTex"), 0)
        glUniform1i(u.getValue("uAlphaTest"), 0)
        glUniform1i(u.getValue("uBlend"), 1)
        glUniform1i(u.getValue("uWater"), 1)
        glUniform3f(u.getValue("uDiffuse"), 1f, 1f, 1f)
        glUniform3f(u.getValue("uEmissive"), 0f, 0f, 0f)
        // the grid is made at height 0 and raised by the view matrix's translation of a lifted copy
        val vp = viewProj(sourceCamera ?: return)
        val lifted = vp.copyOf().also { m -> for (row in 0..3) m[12 + row] += m[8 + row] * h }
        glUniformMatrix4fv(u.getValue("uViewProj"), 1, false, lifted, 0)
        glBindVertexArray(g.vao)
        glDrawElements(GL_TRIANGLES, g.count, GL_UNSIGNED_INT, 0)
        glUniformMatrix4fv(u.getValue("uViewProj"), 1, false, vp, 0)
        glEnable(GL_CULL_FACE)
    }

    private var sourceCamera: OrbitCamera? = null

    /** Projection times view, column-major, matching the software renderer's 60 degree vertical view. */
    private fun viewProj(c: OrbitCamera): FloatArray {
        sourceCamera = c
        val r = c.right; val up = c.up; val fw = c.fwd; val e = c.eye
        // view: rows right, up, -forward
        val view = floatArrayOf(
            r[0], up[0], -fw[0], 0f,
            r[1], up[1], -fw[1], 0f,
            r[2], up[2], -fw[2], 0f,
            -(r[0] * e[0] + r[1] * e[1] + r[2] * e[2]), -(up[0] * e[0] + up[1] * e[1] + up[2] * e[2]), fw[0] * e[0] + fw[1] * e[1] + fw[2] * e[2], 1f,
        )
        val near = 4f; val far = 200000f
        val fy = 1f / tan(PI / 6).toFloat()
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val proj = floatArrayOf(
            fy / aspect, 0f, 0f, 0f,
            0f, fy, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
        val out = FloatArray(16)
        android.opengl.Matrix.multiplyMM(out, 0, proj, 0, view, 0)
        return out
    }

    private fun upload(m: SceneMesh): GlMesh {
        val n = m.positions.size / 3
        val data = ByteBuffer.allocateDirect(n * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uv = m.uvs; val col = m.colors
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until n) {
            val x = m.positions[i * 3]; val y = m.positions[i * 3 + 1]; val z = m.positions[i * 3 + 2]
            cx += x; cy += y; cz += z
            data.put(x).put(y).put(z)
            data.put(m.normals[i * 3]).put(m.normals[i * 3 + 1]).put(m.normals[i * 3 + 2])
            if (uv != null && uv.size >= i * 2 + 2) data.put(uv[i * 2]).put(uv[i * 2 + 1]) else data.put(0f).put(0f)
            if (col != null && col.size >= i * 4 + 4) data.put(col[i * 4]).put(col[i * 4 + 1]).put(col[i * 4 + 2]).put(col[i * 4 + 3])
            else data.put(1f).put(1f).put(1f).put(1f)
        }
        data.position(0)
        val idx = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(m.indices).also { it.position(0) }
        val ids = IntArray(3)
        glGenVertexArrays(1, ids, 0)
        glGenBuffers(2, ids, 1)
        glBindVertexArray(ids[0])
        glBindBuffer(GL_ARRAY_BUFFER, ids[1])
        glBufferData(GL_ARRAY_BUFFER, n * 48, data, GL_STATIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ids[2])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, idx, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 48, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 48, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_FLOAT, false, 48, 24)
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, false, 48, 32)
        glBindVertexArray(0)
        val k = 1f / n.coerceAtLeast(1)
        return GlMesh(ids[0], ids[1], ids[2], m.indices.size, floatArrayOf(cx * k, cy * k, cz * k))
    }

    private fun uploadTexture(b: Bitmap): Int {
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(GL_TEXTURE_2D, id[0])
        for ((level, l) in b.levels.withIndex()) {
            val (w, h, px) = l
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            for (p in px) { buf.put((p ushr 16).toByte()); buf.put((p ushr 8).toByte()); buf.put(p.toByte()); buf.put((p ushr 24).toByte()) }
            buf.position(0)
            glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        }
        // the chain stops at 4x4: tell GL where it ends so the texture is complete
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, b.levels.size - 1)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        return id[0]
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e("CellGl", "shader: " + glGetShaderInfoLog(s))
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e("CellGl", "link: " + glGetProgramInfoLog(p)); return 0 }
        return p
    }
}
