// Frame generation: between rendered frames one more is shown, made from the last two. On Adreno either
// GL_QCOM_frame_extrapolation does it in the driver, or GL_QCOM_motion_estimation (hardware block matching) gives motion
// vectors that a shader uses to place the picture half way along its motion; elsewhere, and on Zink, a block matcher in
// shaders (the "badMB2" post processing shader, ported) finds the vectors. Ported from the fork's
// components/sdlutil/sdlgraphicswindow.cpp (framegen.patch, framegen-ratio-software.patch): here it runs in the swap hook,
// so stock OpenMW needs no change for it.
//
// When the engine hands over its camera and scene textures (androidmain.cpp, frameGenSubmitView), the generated frames are
// made from them instead, as predictions past the rendered frame (where the camera will be if it keeps moving as it did):
// every pixel's depth and the camera matrices say where that point will be, so the world moves correctly whatever its
// texture or distance. The rendered frame goes out as soon as it is done and the predictions carry the time they are to be
// shown, so nothing waits for them (a frame between two rendered ones would hold the newer one back). What comes into
// view from behind something, or at the screen's edges, is stretched from what was next to it. The GUI (whatever the
// window has that the scene image has not) stays where it is, and so does the first person model (the opaque depth has it,
// the scene depth has not). Things that move by themselves are not followed: where the two frames disagree, the newer one
// is shown. Elsewhere (the main menu, loading) the estimators above are used.
//
// The launcher's frame generation switch sets OPENMW_FRAMEGEN (1, motion, extrapolate or software). Other knobs:
// OPENMW_FRAMEGEN_FRAMES (generated frames between two rendered ones, 1 to 3: the launcher's 2x to 4x),
// OPENMW_FRAMEGEN_RATIO (rendered frames per one followed by generated ones), OPENMW_FRAMEGEN_EXTRAPOLATE (push the last frame forward
// instead of showing one between the last two), OPENMW_FRAMEGEN_NOBASELINE, OPENMW_FRAMEGEN_SIGN, OPENMW_FRAMEGEN_TINT
// (generated frames tinted red), OPENMW_FRAMEGEN_ALWAYS (also while the game already runs near the display's rate).
#include "support.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <EGL/egl.h>
#include <glad/gles2.h>
#include <swappy/swappyGL.h>
#include <swappy/swappyGL_extra.h>

#include <osg/FrameStamp>
#include <osg/GraphicsContext>
#include <osg/RenderInfo>
#include <osg/State>

#include <alpha3/upstream.hpp>
#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        using Clock = std::chrono::steady_clock;
        using EstimateMotionFn = void(GL_APIENTRY*)(GLuint, GLuint, GLuint);
        using ExtrapolateFn = void(GL_APIENTRY*)(GLuint, GLuint, GLuint, GLfloat);
        constexpr GLenum MOTION_BLOCK_X = 0x8C90;
        constexpr GLenum MOTION_BLOCK_Y = 0x8C91;

        const char* const sFullscreenVertex = R"(#version 300 es
out vec2 vUV;
void main()
{
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

        const char* const sLumaFragment = R"(#version 300 es
precision highp float;
uniform sampler2D uColor;
in vec2 vUV;
out vec4 oColor;
void main()
{
    oColor = vec4(dot(texture(uColor, vUV).rgb, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
})";

        // The software motion estimator: block matching of a downscaled frame against the one before, in 13x13 searched
        // sections, softened and refined to sub-section accuracy
        const char* const sSwHeader = "#version 300 es\nprecision highp float;\nprecision highp sampler2D;\n"
                                      "in vec2 vUV;\nout vec4 oColor;\n"
                                      "const float SECTIONS = 13.0;\nconst float LIMIT = 0.5;\n";

        const char* const sSwDownBody = R"(
uniform sampler2D uColor;
void main() { oColor = vec4(textureLod(uColor, vUV, 1.5).rgb, 1.0); })";

        const char* const sSwCrossBody = R"(
uniform sampler2D uNext;
uniform sampler2D uOld;
uniform float uNoHistory;
void main()
{
    float n = SECTIONS;
    float q = (SECTIONS - 1.0) / 2.0;
    vec2 uv = vUV * n;
    vec2 uvd = (floor(uv) - q) / q;
    uvd *= abs(uvd);
    uvd *= LIMIT;
    uv = fract(uv);
    vec2 uv2 = uv + uvd * 0.5;
    vec2 ts1 = vec2(1024.0);
    vec2 ts2 = vec2(textureSize(uNext, 0));
    vec2 tsr = ts1 / ts2 / ts2;
    vec3 a = textureGrad(uNext, uv, vec2(tsr.x, 0.0), vec2(0.0, tsr.y)).rgb;
    vec3 b = textureGrad(uOld, uv2, vec2(tsr.x, 0.0), vec2(0.0, tsr.y)).rgb;
    vec3 e = abs(a - b);
    if (uv2.x < 0.0 || uv2.x > 1.0 || uv2.y < 0.0 || uv2.y > 1.0)
        e = max(vec3(0.1), e);
    float vx = vUV.x * 2.0 - 1.0;
    float vy = vUV.y * 2.0 - 1.0;
    e += vec3(vx * vx + vy * vy) * 0.04;
    if (uNoHistory > 0.5)
        e = vec3(0.0);
    oColor = vec4(e, 1.0);
})";

        const char* const sSwBlurBody = R"(
uniform sampler2D uSource;
uniform vec2 uDir;
uniform int uSoften;
void main()
{
    if (uSoften == 0) { oColor = vec4(texture(uSource, vUV).rgb, 1.0); return; }
    float n = float(uSoften) - 0.5;
    vec3 total = vec3(0.0);
    float norm = 0.0;
    vec2 rankX = floor(vUV * SECTIONS);
    vec2 r = 1.0 / vec2(textureSize(uSource, 0));
    for (float t = -n; t <= n; t += 1.0)
    {
        vec2 uv = vUV + uDir * t * r;
        if (floor(uv * SECTIONS) == rankX)
        {
            total += texture(uSource, uv).rgb;
            norm += 1.0;
        }
    }
    oColor = vec4(total / max(1.0, norm), 1.0);
})";

        const char* const sSwMotionBody = R"(
uniform sampler2D uEnergy;
float energy(vec2 uv) { return dot(texture(uEnergy, uv).rgb, vec3(1.0 / 3.0)); }
void main()
{
    float n = SECTIONS;
    vec2 uvq = vUV / n;
    float c = (n - 1.0) / 2.0;
    float best = energy(uvq + vec2(c, c) / n);
    vec2 bestXY = vec2(c, c) / n;
    for (float y = 0.0; y < n; y += 1.0)
        for (float x = 0.0; x < n; x += 1.0)
        {
            float v = energy(uvq + vec2(x, y) / n);
            if (v < best) { bestXY = vec2(x, y) / n; best = v; }
        }
    float xa = 0.0, ya = 0.0;
    if (bestXY.x > 0.0 && bestXY.x + 0.0001 < (n - 1.0) / n)
    {
        float l = energy(uvq + bestXY - vec2(1.0, 0.0) / n);
        float r = energy(uvq + bestXY + vec2(1.0, 0.0) / n);
        xa = (l - r) * 0.5 / (l + r - 2.0 * best + 0.001);
        if (best - ((r - l) * (r - l)) * (1.0 / 8.0) / (r + l - 2.0 * best) < 0.0) xa = 0.0;
    }
    if (bestXY.y > 0.0 && bestXY.y + 0.0001 < (n - 1.0) / n)
    {
        float t = energy(uvq + bestXY - vec2(0.0, 1.0) / n);
        float b = energy(uvq + bestXY + vec2(0.0, 1.0) / n);
        ya = (t - b) * 0.5 / (t + b - 2.0 * best + 0.001);
        if (best - ((t - b) * (t - b)) * (1.0 / 8.0) / (t + b - 2.0 * best) < 0.0) ya = 0.0;
    }
    bestXY += vec2(xa, ya) / n;
    vec2 mv = (bestXY - 0.5 + (0.5 / n)) * 2.0;
    mv *= abs(mv);
    mv = pow(abs(mv), vec2(0.5)) * sign(mv);
    oColor = vec4(mv, 0.0, 1.0);
})";

        // One warp for both estimators. The vectors are the content's displacement over one real frame; the camera's own
        // motion (the average of the field, which is what a pan, turn or walk mostly is) is the baseline, and a local vector
        // is only trusted where it stays near it, so a noisy block cannot tear a piece of the picture off.
        const char* const sWarpFragment = R"(#version 300 es
precision highp float;
uniform sampler2D uColor;
uniform sampler2D uMotion;
uniform vec2 uLumaSize;
uniform float uTime;
uniform float uTint;
uniform float uSoftware;
uniform float uLod;
uniform float uSign;
uniform float uBaseline;
uniform float uTolerance;
uniform sampler2D uColor2;
uniform float uInterp;
in vec2 vUV;
out vec4 oColor;
vec2 decode(vec2 raw)
{
    if (any(isnan(raw)) || any(isinf(raw)))
        return vec2(0.0);
    if (uSoftware > 0.5)
    {
        vec2 mv = -raw;
        mv = pow(abs(mv), vec2(2.0)) * sign(mv);
        return mv * 0.5 * 0.5 * uSign;
    }
    vec2 d = raw / uLumaSize;
    return length(d) > 0.25 ? vec2(0.0) : d;
}
void main()
{
    vec2 d = decode(textureLod(uMotion, vUV, uLod).rg);
    if (uBaseline > 0.5)
    {
        vec2 g = decode(textureLod(uMotion, vec2(0.5), 20.0).rg);
        vec2 resid = d - g;
        float keep = 1.0 - smoothstep(0.5 * uTolerance, uTolerance, length(resid));
        d = g + resid * keep;
    }
    vec3 colour;
    if (uInterp > 0.5)
    {
        // Between the two real frames: the content is uTime of the way along its motion, so each frame is read that far away
        vec3 before = texture(uColor, clamp(vUV - d * uTime, vec2(0.0), vec2(1.0))).rgb;
        vec3 after = texture(uColor2, clamp(vUV + d * (1.0 - uTime), vec2(0.0), vec2(1.0))).rgb;
        colour = mix(before, after, uTime);
    }
    else
        colour = texture(uColor, clamp(vUV - d * uTime, vec2(0.0), vec2(1.0))).rgb;
    oColor = vec4(colour + vec3(0.25, 0.0, 0.0) * uTint, 1.0);
})";

        const char* const sCopyFragment = R"(#version 300 es
precision highp float;
uniform sampler2D uColor;
in vec2 vUV;
out vec4 oColor;
void main()
{
    oColor = vec4(texture(uColor, vUV).rgb, 1.0);
})";

        // A frame between the previous rendered frame and the current one (uTime of the way), from the camera's motion. For
        // each pixel: its depth in the current frame gives its position in view space, the camera's motion gives where that
        // point was in the previous frame, and the point is that far between (looked up again from there, so the depth is the
        // point's and not whatever was in front of it). Both frames are read at their positions and blended, unless they
        // disagree: something moved by itself, or came into view, and the current frame is right about it.
        const char* const sReprojectFragment = R"(#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uFinal;
uniform sampler2D uDepth;
uniform sampler2D uOpaqueDepth;
uniform mat4 uCurToPrev;
uniform vec4 uProj;
uniform float uReversed;
uniform float uTint;
// how far the frame is from the previous one to the current one (0.5: half way). Extrapolating, uCurToPrev goes to the
// predicted next view instead and uTime is 1 minus how far towards it: the previous frame is then not read.
uniform float uTime;
uniform float uExtrapolate;
in vec2 vUV;
out vec4 oColor;
float viewZ(float d)
{
    float n = uProj.z;
    float f = uProj.w;
    if (uReversed > 0.5)
        return -n * f / (d * (f - n) + n);
    return 2.0 * f * n / ((d * 2.0 - 1.0) * (f - n) - (f + n));
}
// Where the point seen at uv in the current frame was in the previous one, as an offset in uv
vec2 motionAt(vec2 uv)
{
    float z = viewZ(texture(uDepth, uv).r);
    // the first person model is drawn over the world with its own depth: it moves with the camera
    if (-viewZ(texture(uOpaqueDepth, uv).r) < -z * 0.9)
        return vec2(0.0);
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 p = uCurToPrev * vec4(ndc.x * -z / uProj.x, ndc.y * -z / uProj.y, z, 1.0);
    if (p.z > -0.001)
        return vec2(0.0);
    vec2 prev = vec2(p.x * uProj.x, p.y * uProj.y) / -p.z;
    return (prev * 0.5 + 0.5) - uv;
}
void main()
{
    vec3 final = texture(uFinal, vUV).rgb;
    // The GUI is drawn over the scene after it: shown as it is now, it does not move with the world
    if (any(greaterThan(abs(final - texture(uCur, vUV).rgb), vec3(0.02))))
    {
        oColor = vec4(final, 1.0);
        return;
    }
    vec2 motion = motionAt(vUV);
    motion = motionAt(clamp(vUV - (1.0 - uTime) * motion, vec2(0.0), vec2(1.0)));
    vec2 inCur = vUV - (1.0 - uTime) * motion;
    vec2 inPrev = vUV + uTime * motion;
    vec3 cur = texture(uCur, clamp(inCur, vec2(0.0), vec2(1.0))).rgb;
    vec3 prev = texture(uPrev, clamp(inPrev, vec2(0.0), vec2(1.0))).rgb;
    vec3 d = abs(cur - prev);
    float weight = (1.0 - uExtrapolate) * (1.0 - uTime) * (1.0 - smoothstep(0.06, 0.15, max(d.r, max(d.g, d.b))));
    if (any(lessThan(inPrev, vec2(0.0))) || any(greaterThan(inPrev, vec2(1.0))))
        weight = 0.0;
    oColor = vec4(mix(cur, prev, weight) + vec3(0.25, 0.0, 0.0) * uTint, 1.0);
})";

        // Zink is desktop OpenGL: the shaders are written for OpenGL ES 3.00 and become 3.30 core (no precision lines)
        bool sDesktopShaders = false;

        std::string toDesktop(const char* source)
        {
            std::string text = source;
            const std::size_t version = text.find("#version 300 es");
            if (version != std::string::npos)
                text.replace(version, 15, "#version 330 core");
            std::size_t precision;
            while ((precision = text.find("precision ")) != std::string::npos)
                text.erase(precision, text.find('\n', precision) - precision + 1);
            return text;
        }

        GLuint compileProgram(const char* vertexSource, const char* fragmentSource)
        {
            const auto compile = [](GLenum type, const char* original) {
                const std::string converted = sDesktopShaders ? toDesktop(original) : std::string();
                const char* source = sDesktopShaders ? converted.c_str() : original;
                GLuint shader = glCreateShader(type);
                glShaderSource(shader, 1, &source, nullptr);
                glCompileShader(shader);
                GLint ok = 0;
                glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
                if (!ok)
                {
                    char log[512];
                    glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
                    Log(Debug::Warning) << "Frame generation shader: " << log;
                    glDeleteShader(shader);
                    return GLuint(0);
                }
                return shader;
            };
            const GLuint vertex = compile(GL_VERTEX_SHADER, vertexSource);
            const GLuint fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
            if (vertex == 0 || fragment == 0)
                return 0;
            GLuint program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            GLint ok = 0;
            glGetProgramiv(program, GL_LINK_STATUS, &ok);
            if (!ok)
            {
                glDeleteProgram(program);
                return 0;
            }
            return program;
        }

        // A program's uniform locations, looked up once when it is linked (a lookup is a string search in the driver)
        struct WarpUniforms
        {
            GLint mColor, mMotion, mLumaSize, mTime, mTint, mSoftware, mLod, mSign, mBaseline, mTolerance, mColor2,
                mInterp;
            void find(GLuint p)
            {
                mColor = glGetUniformLocation(p, "uColor");
                mMotion = glGetUniformLocation(p, "uMotion");
                mLumaSize = glGetUniformLocation(p, "uLumaSize");
                mTime = glGetUniformLocation(p, "uTime");
                mTint = glGetUniformLocation(p, "uTint");
                mSoftware = glGetUniformLocation(p, "uSoftware");
                mLod = glGetUniformLocation(p, "uLod");
                mSign = glGetUniformLocation(p, "uSign");
                mBaseline = glGetUniformLocation(p, "uBaseline");
                mTolerance = glGetUniformLocation(p, "uTolerance");
                mColor2 = glGetUniformLocation(p, "uColor2");
                mInterp = glGetUniformLocation(p, "uInterp");
            }
        };

        class FrameGen
        {
        public:
            /// Null when the driver has neither extension (or the one asked for). request: 1/auto, motion, extrapolate or
            /// software.
            static FrameGen* create(const std::string& request, bool desktop)
            {
                sDesktopShaders = desktop;
                const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
                const std::string list = extensions != nullptr ? extensions : "";
                const bool hasMotion = list.find("GL_QCOM_motion_estimation") != std::string::npos;
                const bool hasExtrapolation = list.find("GL_QCOM_frame_extrapolation") != std::string::npos;
                std::unique_ptr<FrameGen> frames(new FrameGen);
                if (hasExtrapolation && request != "motion" && request != "software")
                {
                    frames->mExtrapolate = reinterpret_cast<ExtrapolateFn>(eglGetProcAddress("glExtrapolateTex2DQCOM"));
                    if (frames->mExtrapolate != nullptr)
                        frames->mMode = Mode::Extrapolate;
                }
                if (frames->mMode == Mode::Off && hasMotion && request != "extrapolate" && request != "software")
                {
                    frames->mEstimate = reinterpret_cast<EstimateMotionFn>(eglGetProcAddress("glTexEstimateMotionQCOM"));
                    if (frames->mEstimate != nullptr)
                        frames->mMode = Mode::Motion;
                }
                // Without the Qualcomm extensions (and on Zink, which never has them) the shader estimator is used
                if (frames->mMode == Mode::Off)
                    frames->mMode = Mode::Software;
                glGetIntegerv(MOTION_BLOCK_X, &frames->mBlockX);
                glGetIntegerv(MOTION_BLOCK_Y, &frames->mBlockY);
                glGetError();
                frames->mBlockX = std::max(frames->mBlockX, 1);
                frames->mBlockY = std::max(frames->mBlockY, 1);
                frames->mLumaProgram = compileProgram(sFullscreenVertex, sLumaFragment);
                frames->mWarpProgram = compileProgram(sFullscreenVertex, sWarpFragment);
                if (frames->mMode == Mode::Software)
                {
                    const auto build = [](const char* body) {
                        return compileProgram(sFullscreenVertex, (std::string(sSwHeader) + body).c_str());
                    };
                    frames->mSw[0] = build(sSwDownBody);
                    frames->mSw[1] = build(sSwCrossBody);
                    frames->mSw[2] = build(sSwBlurBody);
                    frames->mSw[3] = build(sSwMotionBody);
                    for (GLuint program : frames->mSw)
                        if (program == 0)
                            return nullptr;
                }
                frames->mBaseline = std::getenv("OPENMW_FRAMEGEN_NOBASELINE") == nullptr;
                if (const char* ratio = std::getenv("OPENMW_FRAMEGEN_RATIO"))
                    frames->mRatio = std::max(1, std::atoi(ratio));
                else
                    frames->mRatio = 1; // the ramp sets it (not on Zink: one generated frame for every real one)
                if (const char* count = std::getenv("OPENMW_FRAMEGEN_FRAMES"))
                    frames->mMaxFrames = std::clamp(std::atoi(count), 1, 3);
                if (const char* sign = std::getenv("OPENMW_FRAMEGEN_SIGN"))
                    frames->mSign = static_cast<float>(std::atof(sign));
                frames->mCopyProgram = compileProgram(sFullscreenVertex, sCopyFragment);
                if (frames->mLumaProgram == 0 || frames->mWarpProgram == 0 || frames->mCopyProgram == 0)
                    return nullptr;
                frames->mWarp.find(frames->mWarpProgram);
                frames->mReprojectProgram = compileProgram(sFullscreenVertex, sReprojectFragment);
                if (frames->mReprojectProgram != 0)
                {
                    const GLuint p = frames->mReprojectProgram;
                    frames->mRpCur = glGetUniformLocation(p, "uCur");
                    frames->mRpPrev = glGetUniformLocation(p, "uPrev");
                    frames->mRpFinal = glGetUniformLocation(p, "uFinal");
                    frames->mRpDepth = glGetUniformLocation(p, "uDepth");
                    frames->mRpOpaqueDepth = glGetUniformLocation(p, "uOpaqueDepth");
                    frames->mRpCurToPrev = glGetUniformLocation(p, "uCurToPrev");
                    frames->mRpProj = glGetUniformLocation(p, "uProj");
                    frames->mRpReversed = glGetUniformLocation(p, "uReversed");
                    frames->mRpTint = glGetUniformLocation(p, "uTint");
                    frames->mRpTime = glGetUniformLocation(p, "uTime");
                    frames->mRpExtrapolate = glGetUniformLocation(p, "uExtrapolate");
                }
                else
                    Log(Debug::Warning) << "Frame generation: the reprojection shader did not build, motion estimation only";
                frames->mRatioSet = std::getenv("OPENMW_FRAMEGEN_RATIO") != nullptr;
                frames->mLumaColor = glGetUniformLocation(frames->mLumaProgram, "uColor");
                frames->mCopyColor = glGetUniformLocation(frames->mCopyProgram, "uColor");
                if (frames->mMode == Mode::Software)
                {
                    frames->mSwDownColor = glGetUniformLocation(frames->mSw[0], "uColor");
                    frames->mSwCrossNext = glGetUniformLocation(frames->mSw[1], "uNext");
                    frames->mSwCrossOld = glGetUniformLocation(frames->mSw[1], "uOld");
                    frames->mSwCrossNoHistory = glGetUniformLocation(frames->mSw[1], "uNoHistory");
                    frames->mSwBlurSource = glGetUniformLocation(frames->mSw[2], "uSource");
                    frames->mSwBlurDir = glGetUniformLocation(frames->mSw[2], "uDir");
                    frames->mSwBlurSoften = glGetUniformLocation(frames->mSw[2], "uSoften");
                    frames->mSwMotionEnergy = glGetUniformLocation(frames->mSw[3], "uEnergy");
                }
                glGenVertexArrays(1, &frames->mVao);
                glGenFramebuffers(1, &frames->mFbo);
                Log(Debug::Info) << "Frame generation: "
                                 << (frames->mMode == Mode::Extrapolate ? "GL_QCOM_frame_extrapolation"
                                         : frames->mMode == Mode::Software ? "software motion estimation (block matching)"
                                                                           : "GL_QCOM_motion_estimation, search block ")
                                 << (frames->mMode == Mode::Motion
                                            ? std::to_string(frames->mBlockX) + "x" + std::to_string(frames->mBlockY)
                                            : std::string());
                return frames.release();
            }

            ~FrameGen() { release(); }

            /// After a real frame is drawn, before it is shown: remember it. reprojected: the frame between is made from the
            /// engine's camera and depth, so the estimators' inputs are not made (they start again when they are needed).
            void capture(int width, int height, bool reprojected)
            {
                const Clock::time_point now = Clock::now();
                if (mLastCapture != Clock::time_point())
                {
                    const double ms = std::chrono::duration<double, std::milli>(now - mLastCapture).count();
                    mInterval = mInterval == 0.0 ? ms : mInterval * 0.8 + ms * 0.2;
                }
                mLastCapture = now;
                if (!prepare(width, height))
                    return;
                Saved saved = begin();
                mCurrent = 1 - mCurrent;
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glBindTexture(GL_TEXTURE_2D, mColor[mCurrent]);
                glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
                if (reprojected)
                    mEstimatorStale = true;
                else if (mMode == Mode::Software)
                    glGenerateMipmap(GL_TEXTURE_2D);
                if (mMode == Mode::Motion && !reprojected)
                {
                    glBindFramebuffer(GL_FRAMEBUFFER, mFbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mLuma[mCurrent], 0);
                    glViewport(0, 0, mLumaWidth, mLumaHeight);
                    glUseProgram(mLumaProgram);
                    glUniform1i(mLumaColor, 0);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                }
                mHistory = std::min(mHistory + 1, 3);
                // the estimators compare against frames they did not see: they need two again
                if (!reprojected && mEstimatorStale)
                {
                    mEstimatorStale = false;
                    mHistory = 1;
                }
                ++mReal;
                end(saved);
            }

            /// The window's image once the post processor has drawn it, before the GUI goes over it.
            void capturePreGui(unsigned frame)
            {
                if (mPreGui[0] == 0)
                    return;
                Saved saved = begin();
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glBindTexture(GL_TEXTURE_2D, mPreGui[frame % 2]);
                glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, mWidth, mHeight);
                mPreGuiFrame[frame % 2] = frame;
                end(saved);
            }

            bool hasPreGui(unsigned frame) const { return mPreGui[0] != 0 && mPreGuiFrame[frame % 2] == frame; }

            bool canReproject() const { return mReprojectProgram != 0; }

            /// A frame from the camera's motion and the depth: between the previous rendered frame and this one (time: how
            /// far along, 0.5 half way), or extrapolating, past this one (time: how far towards where the camera will be
            /// at the next rendered frame, if it keeps moving as it did).
            bool generateReprojected(const FrameGenView& cur, const FrameGenView& prev, GLuint scene, GLuint prevScene,
                GLuint depth, GLuint opaqueDepth, float time, bool extrapolate)
            {
                if (mHistory < 1 || mWidth == 0)
                    return false;
                double fovy = 0, aspect = 0, zNear = 0, zFar = 0;
                if (!cur.projection.getPerspective(fovy, aspect, zNear, zFar) || zNear <= 0.0 || zFar <= zNear)
                    return false;
                // view space of this frame to view space of the previous one: small numbers, worked out in double
                const osg::Matrixd curToPrevD = osg::Matrixd::inverse(cur.view) * prev.view;
                // keeping on as it did: from this view, the next moves as this one did from the previous
                const osg::Matrixf curToPrev(extrapolate ? osg::Matrixd::inverse(curToPrevD) : curToPrevD);
                Saved saved = begin();
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, mWidth, mHeight);
                glUseProgram(mReprojectProgram);
                const auto input = [](int unit, GLint location, GLuint texture) {
                    glActiveTexture(GL_TEXTURE0 + unit);
                    glBindTexture(GL_TEXTURE_2D, texture);
                    glUniform1i(location, unit);
                };
                input(0, mRpCur, cur.postProcessing ? mPreGui[cur.frame % 2] : scene);
                input(1, mRpPrev, cur.postProcessing ? mPreGui[prev.frame % 2] : prevScene);
                input(2, mRpFinal, mColor[mCurrent]);
                input(3, mRpDepth, depth);
                input(4, mRpOpaqueDepth, opaqueDepth);
                glUniformMatrix4fv(mRpCurToPrev, 1, GL_FALSE, curToPrev.ptr());
                glUniform4f(mRpProj, static_cast<float>(cur.projection(0, 0)), static_cast<float>(cur.projection(1, 1)),
                    static_cast<float>(zNear), static_cast<float>(zFar));
                glUniform1f(mRpReversed, cur.reversedDepth ? 1.0f : 0.0f);
                glUniform1f(mRpTint, mTint ? 1.0f : 0.0f);
                glUniform1f(mRpTime, extrapolate ? 1.0f - time : time);
                glUniform1f(mRpExtrapolate, extrapolate ? 1.0f : 0.0f);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                for (int unit = 4; unit >= 3; --unit)
                {
                    glActiveTexture(GL_TEXTURE0 + unit);
                    glBindTexture(GL_TEXTURE_2D, 0);
                }
                mReprojectedFrames++;
                logUse();
                end(saved);
                return true;
            }

            /// How many generated frames go out, after how many rendered ones (the ramp's step; OPENMW_FRAMEGEN_RATIO
            /// fixes the ratio)
            void setLevel(int ratio, int count)
            {
                if (!mRatioSet)
                    mRatio = ratio;
                mFrames = std::min(count, mMaxFrames);
            }

            int maxFrames() const { return mMaxFrames; }

            /// Whether generated frames go out with this real one. Not while the game already renders at nearly the
            /// display's rate (fast, from the cadence): there they only take GPU time from the real frames, and there is
            /// no gap to fill.
            bool due(bool fast)
            {
                if (mHistory < 2 || mWidth == 0 || mReal % mRatio != 0)
                    return false;
                fast = fast && !mAlways;
                if (fast != mResting)
                {
                    mResting = fast;
                    Log(Debug::Info) << "Frame generation " << (fast ? "resting" : "resumed") << ": rendered frames come every "
                                     << std::fixed << std::setprecision(1) << mInterval << " ms";
                }
                return !fast;
            }

            /// Generated frames between two rendered ones (OPENMW_FRAMEGEN_FRAMES, 1 to 3)
            int frames() const { return mFrames; }

            bool resting() const { return mResting; }

            bool interpolates() const { return mInterpolate && (mMode == Mode::Motion || mMode == Mode::Software); }

            /// Draws the extra frame, time of the way from the previous real frame to this one, into the back buffer; false
            /// when there is nothing to show yet.
            bool generate(bool interpolate, float time)
            {
                if (mHistory < 2 || mWidth == 0)
                    return false;
                const int previous = 1 - mCurrent;
                Saved saved = begin();
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, mWidth, mHeight);
                if (mMode == Mode::Extrapolate)
                {
                    mExtrapolate(mColor[previous], mColor[mCurrent], mOutput, time);
                    glUseProgram(mCopyProgram);
                    glUniform1i(mCopyColor, 0);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, mOutput);
                }
                else
                {
                    if (mEstimatedFor != mReal)
                    {
                        mEstimatedFor = mReal;
                        if (mMode == Mode::Software)
                            estimateSoftware();
                        else
                        {
                            mEstimate(mLuma[previous], mLuma[mCurrent], mMotion);
                            if (const GLenum error = glGetError(); error != GL_NO_ERROR && mErrors++ < 3)
                                Log(Debug::Warning) << "Frame generation: motion estimation error 0x" << std::hex << error;
                            glBindTexture(GL_TEXTURE_2D, mMotion);
                            glGenerateMipmap(GL_TEXTURE_2D);
                        }
                    }
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glViewport(0, 0, mWidth, mHeight);
                    const bool software = mMode == Mode::Software;
                    glUseProgram(mWarpProgram);
                    glUniform1i(mWarp.mColor, 0);
                    glUniform1i(mWarp.mMotion, 1);
                    glUniform2f(mWarp.mLumaSize, static_cast<float>(mLumaWidth), static_cast<float>(mLumaHeight));
                    glUniform1f(mWarp.mTime, time);
                    glUniform1f(mWarp.mTint, mTint ? 1.0f : 0.0f);
                    glUniform1f(mWarp.mSoftware, software ? 1.0f : 0.0f);
                    glUniform1f(mWarp.mLod, software ? 2.0f : 0.0f);
                    glUniform1f(mWarp.mSign, mSign);
                    glUniform1f(mWarp.mBaseline, mBaseline ? 1.0f : 0.0f);
                    glUniform1f(mWarp.mTolerance, software ? 0.04f : 0.02f);
                    glUniform1i(mWarp.mColor2, 2);
                    glUniform1f(mWarp.mInterp, interpolate ? 1.0f : 0.0f);
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, mColor[mCurrent]);
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D, software ? mMV : mMotion);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, interpolate ? mColor[previous] : mColor[mCurrent]);
                }
                glDrawArrays(GL_TRIANGLES, 0, 3);
                logUse();
                end(saved);
                return true;
            }

            /// Puts the frame that was just rendered back into the back buffer after the extra one went out.
            void showCurrent()
            {
                Saved saved = begin();
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, mWidth, mHeight);
                glUseProgram(mCopyProgram);
                glUniform1i(mCopyColor, 0);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, mColor[mCurrent]);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                end(saved);
            }

            /// Time between the last two real frames, smoothed (what the next one is predicted to take).
            double interval() const { return mInterval; }

        private:
            enum class Mode
            {
                Off,
                Motion,
                Extrapolate,
                Software
            };

            /// Every few seconds: proof in the log that generated frames are shown, and how many.
            void logUse()
            {
                ++mGenerated;
                const Clock::time_point now = Clock::now();
                if (mLogStart == Clock::time_point())
                    mLogStart = now;
                const double seconds = std::chrono::duration<double>(now - mLogStart).count();
                if (seconds < 5.0)
                    return;
                const int real = mReal - mRealLogged;
                Log(Debug::Info) << "Frame generation active ("
                                 << (mMode == Mode::Extrapolate ? "frame extrapolation"
                                         : mMode == Mode::Software ? "software motion estimation"
                                                                   : "motion estimation")
                                 << ", " << mFrames << " generated after every " << mRatio << " rendered): " << std::fixed
                                 << std::setprecision(1) << real / seconds << " rendered + " << mGenerated / seconds
                                 << " generated = " << (real + mGenerated) / seconds << " shown frames per second, "
                                 << mReprojectedFrames << " of the generated from the camera and depth";
                mReprojectedFrames = 0;
                mRealLogged = mReal;
                mGenerated = 0;
                mLogStart = now;
            }

            struct Saved
            {
                GLint mActiveTexture = GL_TEXTURE0;
                GLint mProgram = 0;
                GLint mVao = 0;
                GLint mFramebuffer = 0;
                GLint mViewport[4] = {};
                GLint mTexture0 = 0;
                GLint mTexture1 = 0;
                GLint mTexture2 = 0;
                GLint mColorMask[4] = {};
                GLboolean mDepth = GL_FALSE;
                GLboolean mBlend = GL_FALSE;
                GLboolean mCull = GL_FALSE;
                GLboolean mScissor = GL_FALSE;
                GLboolean mStencil = GL_FALSE;
            };

            // Everything touched here is put back exactly, so OSG's own state tracking stays right
            Saved begin()
            {
                Saved saved;
                glGetIntegerv(GL_ACTIVE_TEXTURE, &saved.mActiveTexture);
                glGetIntegerv(GL_CURRENT_PROGRAM, &saved.mProgram);
                glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &saved.mVao);
                glGetIntegerv(GL_FRAMEBUFFER_BINDING, &saved.mFramebuffer);
                glGetIntegerv(GL_VIEWPORT, saved.mViewport);
                glGetIntegerv(GL_COLOR_WRITEMASK, saved.mColorMask);
                glActiveTexture(GL_TEXTURE0);
                glGetIntegerv(GL_TEXTURE_BINDING_2D, &saved.mTexture0);
                glActiveTexture(GL_TEXTURE1);
                glGetIntegerv(GL_TEXTURE_BINDING_2D, &saved.mTexture1);
                glActiveTexture(GL_TEXTURE2);
                glGetIntegerv(GL_TEXTURE_BINDING_2D, &saved.mTexture2);
                glActiveTexture(GL_TEXTURE0);
                saved.mDepth = glIsEnabled(GL_DEPTH_TEST);
                saved.mBlend = glIsEnabled(GL_BLEND);
                saved.mCull = glIsEnabled(GL_CULL_FACE);
                saved.mScissor = glIsEnabled(GL_SCISSOR_TEST);
                saved.mStencil = glIsEnabled(GL_STENCIL_TEST);
                glBindVertexArray(mVao);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_BLEND);
                glDisable(GL_CULL_FACE);
                glDisable(GL_SCISSOR_TEST);
                glDisable(GL_STENCIL_TEST);
                glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
                return saved;
            }

            void end(const Saved& saved)
            {
                const auto set = [](GLenum capability, GLboolean on) {
                    if (on)
                        glEnable(capability);
                    else
                        glDisable(capability);
                };
                set(GL_DEPTH_TEST, saved.mDepth);
                set(GL_BLEND, saved.mBlend);
                set(GL_CULL_FACE, saved.mCull);
                set(GL_SCISSOR_TEST, saved.mScissor);
                set(GL_STENCIL_TEST, saved.mStencil);
                glColorMask(saved.mColorMask[0], saved.mColorMask[1], saved.mColorMask[2], saved.mColorMask[3]);
                glActiveTexture(GL_TEXTURE2);
                glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(saved.mTexture2));
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(saved.mTexture1));
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(saved.mTexture0));
                glActiveTexture(static_cast<GLenum>(saved.mActiveTexture));
                glBindVertexArray(static_cast<GLuint>(saved.mVao));
                glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(saved.mFramebuffer));
                glViewport(saved.mViewport[0], saved.mViewport[1], saved.mViewport[2], saved.mViewport[3]);
                glUseProgram(static_cast<GLuint>(saved.mProgram));
                while (glGetError() != GL_NO_ERROR)
                {
                }
            }

            /// The ported estimator: current frame down to 512x256, cross energy against the previous one, softening, the
            /// search for the best section, softening of the vectors, then the frame becomes the previous one.
            void estimateSoftware()
            {
                const auto target = [&](GLuint program, GLuint texture, int width, int height) {
                    glBindFramebuffer(GL_FRAMEBUFFER, mFbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                    glViewport(0, 0, width, height);
                    glUseProgram(program);
                };
                const auto input = [](int unit, GLint location, GLuint texture) {
                    glActiveTexture(GL_TEXTURE0 + unit);
                    glBindTexture(GL_TEXTURE_2D, texture);
                    glUniform1i(location, unit);
                };
                const auto mips = [](GLuint texture) {
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, texture);
                    glGenerateMipmap(GL_TEXTURE_2D);
                };
                const auto blur = [&](GLuint source, GLuint texture, bool large, float x, float y) {
                    target(mSw[2], texture, large ? 1024 : 512, large ? 1024 : 256);
                    glUniform2f(mSwBlurDir, x, y);
                    glUniform1i(mSwBlurSoften, 4);
                    input(0, mSwBlurSource, source);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                };
                target(mSw[0], mNext, 512, 256);
                input(0, mSwDownColor, mColor[mCurrent]);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                mips(mNext);
                target(mSw[1], mCE[0], 1024, 1024);
                glUniform1f(mSwCrossNoHistory, mHistory < 3 ? 1.0f : 0.0f);
                input(0, mSwCrossNext, mNext);
                input(1, mSwCrossOld, mOld);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                blur(mCE[0], mCE[1], true, 0.0f, 1.0f);
                blur(mCE[1], mCE[0], true, 1.0f, 0.0f);
                target(mSw[3], mMV, 512, 256);
                input(0, mSwMotionEnergy, mCE[0]);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                blur(mMV, mMV2, false, 0.0f, 1.0f);
                blur(mMV2, mMV, false, 1.0f, 0.0f);
                mips(mMV);
                target(mCopyProgram, mOld, 512, 256);
                input(0, mCopyColor, mNext);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                mips(mOld);
                glActiveTexture(GL_TEXTURE0);
            }

            GLuint makeTexture(GLenum internalFormat, int width, int height, GLenum filter, int levels = 1)
            {
                GLuint texture = 0;
                glGenTextures(1, &texture);
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexStorage2D(GL_TEXTURE_2D, levels, internalFormat, width, height);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, static_cast<GLint>(filter));
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                return texture;
            }

            bool prepare(int width, int height)
            {
                if (width <= 0 || height <= 0)
                    return false;
                if (width == mWidth && height == mHeight && mColor[0] != 0)
                    return true;
                Saved saved = begin();
                destroyTextures();
                mWidth = width;
                mHeight = height;
                mHistory = 0;
                const auto levelsFor = [](int w, int h) {
                    int levels = 1;
                    for (int size = std::max(w, h); size > 1; size >>= 1)
                        ++levels;
                    return levels;
                };
                for (int i = 0; i < 2; ++i)
                    mColor[i] = mMode == Mode::Software
                        ? makeTexture(GL_RGB8, width, height, GL_LINEAR_MIPMAP_LINEAR, levelsFor(width, height))
                        : makeTexture(GL_RGB8, width, height, GL_LINEAR);
                for (int i = 0; i < 2; ++i)
                {
                    mPreGui[i] = makeTexture(GL_RGB8, width, height, GL_LINEAR);
                    mPreGuiFrame[i] = ~0u;
                }
                if (mMode == Mode::Extrapolate)
                    mOutput = makeTexture(GL_RGB8, width, height, GL_LINEAR);
                else if (mMode == Mode::Software)
                {
                    const int levels = levelsFor(512, 256);
                    mNext = makeTexture(GL_RGBA16F, 512, 256, GL_LINEAR_MIPMAP_LINEAR, levels);
                    mOld = makeTexture(GL_RGBA16F, 512, 256, GL_LINEAR_MIPMAP_LINEAR, levels);
                    mMV = makeTexture(GL_RGBA16F, 512, 256, GL_LINEAR_MIPMAP_LINEAR, levels);
                    mMV2 = makeTexture(GL_RGBA16F, 512, 256, GL_LINEAR);
                    mCE[0] = makeTexture(GL_RGBA16F, 1024, 1024, GL_LINEAR);
                    mCE[1] = makeTexture(GL_RGBA16F, 1024, 1024, GL_LINEAR);
                    mLumaWidth = 512;
                    mLumaHeight = 256;
                    Log(Debug::Info) << "Frame generation: " << width << "x" << height
                                     << ", software motion estimation on 512x256";
                }
                else
                {
                    // Block matching on a quarter size luma image, each side a multiple of the search block
                    mLumaWidth = std::max(mBlockX, (width / 4) / mBlockX * mBlockX);
                    mLumaHeight = std::max(mBlockY, (height / 4) / mBlockY * mBlockY);
                    for (int i = 0; i < 2; ++i)
                        mLuma[i] = makeTexture(GL_R8, mLumaWidth, mLumaHeight, GL_LINEAR);
                    mMotion = makeTexture(GL_RGBA16F, mLumaWidth / mBlockX, mLumaHeight / mBlockY,
                        GL_LINEAR_MIPMAP_LINEAR, levelsFor(mLumaWidth / mBlockX, mLumaHeight / mBlockY));
                    Log(Debug::Info) << "Frame generation: " << width << "x" << height << ", motion on " << mLumaWidth
                                     << "x" << mLumaHeight << " luma, " << mLumaWidth / mBlockX << "x"
                                     << mLumaHeight / mBlockY << " vectors";
                }
                end(saved);
                return true;
            }

            void destroyTextures()
            {
                for (GLuint* texture : { &mColor[0], &mColor[1], &mLuma[0], &mLuma[1], &mMotion, &mOutput, &mNext, &mOld,
                         &mMV, &mMV2, &mCE[0], &mCE[1], &mPreGui[0], &mPreGui[1] })
                    if (*texture != 0)
                    {
                        glDeleteTextures(1, texture);
                        *texture = 0;
                    }
            }

            void release()
            {
                destroyTextures();
                if (mVao != 0)
                    glDeleteVertexArrays(1, &mVao);
                if (mFbo != 0)
                    glDeleteFramebuffers(1, &mFbo);
                for (GLuint program :
                    { mLumaProgram, mWarpProgram, mCopyProgram, mReprojectProgram, mSw[0], mSw[1], mSw[2], mSw[3] })
                    if (program != 0)
                        glDeleteProgram(program);
                mVao = mFbo = mLumaProgram = mWarpProgram = mCopyProgram = mReprojectProgram = 0;
                mSw[0] = mSw[1] = mSw[2] = mSw[3] = 0;
            }

            Mode mMode = Mode::Off;
            EstimateMotionFn mEstimate = nullptr;
            ExtrapolateFn mExtrapolate = nullptr;
            GLint mBlockX = 1;
            GLint mBlockY = 1;
            const bool mTint = std::getenv("OPENMW_FRAMEGEN_TINT") != nullptr;
            const bool mAlways = std::getenv("OPENMW_FRAMEGEN_ALWAYS") != nullptr;
            const bool mInterpolate = std::getenv("OPENMW_FRAMEGEN_EXTRAPOLATE") == nullptr;
            bool mBaseline = true;
            bool mResting = false;
            float mSign = 1.0f;
            int mRatio = 2;
            // generated frames between two rendered ones: now (the ramp's step) and at most (the launcher's multiplier)
            int mFrames = 1;
            int mMaxFrames = 1;
            // the real frame the motion vectors were last estimated for: every frame between it and the one before uses them
            int mEstimatedFor = -1;
            int mGenerated = 0;
            int mReal = 0;
            int mRealLogged = 0;
            int mErrors = 0;
            double mInterval = 0.0;
            Clock::time_point mLastCapture;
            Clock::time_point mLogStart;

            GLuint mReprojectProgram = 0;
            GLint mRpCur = -1, mRpPrev = -1, mRpFinal = -1, mRpDepth = -1, mRpOpaqueDepth = -1, mRpCurToPrev = -1,
                  mRpProj = -1, mRpReversed = -1, mRpTint = -1, mRpTime = -1, mRpExtrapolate = -1;
            GLuint mPreGui[2] = { 0, 0 };
            unsigned mPreGuiFrame[2] = { ~0u, ~0u };
            bool mEstimatorStale = false;
            bool mRatioSet = false;
            int mReprojectedFrames = 0;
            GLuint mLumaProgram = 0;
            GLuint mWarpProgram = 0;
            GLuint mCopyProgram = 0;
            GLuint mSw[4] = { 0, 0, 0, 0 };
            WarpUniforms mWarp{};
            GLint mLumaColor = -1, mCopyColor = -1;
            GLint mSwDownColor = -1, mSwCrossNext = -1, mSwCrossOld = -1, mSwCrossNoHistory = -1;
            GLint mSwBlurSource = -1, mSwBlurDir = -1, mSwBlurSoften = -1, mSwMotionEnergy = -1;
            GLuint mVao = 0;
            GLuint mFbo = 0;
            GLuint mColor[2] = { 0, 0 };
            GLuint mLuma[2] = { 0, 0 };
            GLuint mMotion = 0;
            GLuint mOutput = 0;
            GLuint mNext = 0, mOld = 0, mMV = 0, mMV2 = 0;
            GLuint mCE[2] = { 0, 0 };
            int mWidth = 0;
            int mHeight = 0;
            int mLumaWidth = 0;
            int mLumaHeight = 0;
            int mCurrent = 0;
            int mHistory = 0;
        };

        bool requested()
        {
            const char* request = std::getenv("OPENMW_FRAMEGEN");
            return request != nullptr && *request != '\0' && std::strcmp(request, "0") != 0;
        }

        // How much is generated ramps up while the rendered frames keep their rate: one generated frame after every fifth
        // rendered one, then every fourth, third, second, every one, then two and three after each (up to the launcher's
        // multiplier). A step that costs the rendered frames more than a fifth of their rate is taken back, and the ramp
        // waits before trying it again. Nothing is held back for it: the rendered frames go out as soon as they are done.
        struct Ramp
        {
            static constexpr int sRatios[] = { 5, 4, 3, 2, 1 };
            static constexpr int sRatioSteps = 5;

            Clock::time_point mWindowStart = Clock::now();
            Clock::time_point mStepBackAt;
            int mWindowFrames = 0;
            int mStep = 0;
            double mReference = 0.0;
            bool mBackedOff = false;

            int ratio() const { return mStep < sRatioSteps ? sRatios[mStep] : 1; }
            int count() const { return mStep < sRatioSteps ? 1 : 1 + mStep - (sRatioSteps - 1); }

            /// At a rendered frame
            void frame(Clock::time_point now, int maxFrames, bool resting)
            {
                ++mWindowFrames;
                const std::chrono::duration<double> window = now - mWindowStart;
                if (window < std::chrono::seconds(2))
                    return;
                const double rate = mWindowFrames / window.count();
                mWindowStart = now;
                mWindowFrames = 0;
                if (resting)
                {
                    // back at the start when frames are needed again
                    mStep = 0;
                    mReference = 0.0;
                    return;
                }
                if (mReference == 0.0)
                {
                    mReference = rate;
                    return;
                }
                if (rate < mReference * 0.8 && mStep > 0)
                {
                    --mStep;
                    mBackedOff = true;
                    mStepBackAt = now;
                    Log(Debug::Info) << "Frame generation: rendered frames fell to " << std::fixed << std::setprecision(1)
                                     << rate << " per second (from " << mReference << "), back to " << count()
                                     << " generated after every " << ratio() << " rendered";
                    return;
                }
                const int top = sRatioSteps - 1 + maxFrames;
                if (rate >= mReference * 0.9 && mStep < top
                    && (!mBackedOff || now - mStepBackAt > std::chrono::seconds(20)))
                {
                    ++mStep;
                    mBackedOff = false;
                    Log(Debug::Info) << "Frame generation: rendered frames at " << std::fixed << std::setprecision(1)
                                     << rate << " per second, up to " << count() << " generated after every " << ratio()
                                     << " rendered";
                }
                // the reference follows the rate where the ramp settles, so a slower scene does not count as its cost
                if (!mBackedOff)
                    mReference += (rate - mReference) * 0.2;
            }
        };

        struct State
        {
            bool mTried = false;
            std::unique_ptr<FrameGen> mFrames;
            Ramp mRamp;
        };

        // Used on the draw thread only
        State& state()
        {
            static State sState;
            return sState;
        }

        // What the main thread handed over for the last few frames, by frame number
        std::mutex sViewsMutex;
        std::array<FrameGenView, 4> sViews;

        bool findView(unsigned frame, FrameGenView& out)
        {
            std::lock_guard lock(sViewsMutex);
            const FrameGenView& view = sViews[frame % sViews.size()];
            if (view.frame != frame || !view.scene || !view.depth || !view.opaqueDepth)
                return false;
            out = view;
            return true;
        }

        GLuint textureId(const osg::Texture& texture, unsigned contextId)
        {
            const osg::Texture::TextureObject* object = texture.getTextureObject(contextId);
            return object != nullptr ? object->id() : 0;
        }

        class PreGuiCapture : public osg::Camera::DrawCallback
        {
        public:
            void operator()(osg::RenderInfo& renderInfo) const override
            {
                FrameGen* frames = state().mFrames.get();
                const osg::State* glState = renderInfo.getState();
                if (frames == nullptr || glState == nullptr || glState->getFrameStamp() == nullptr)
                    return;
                const unsigned frame = glState->getFrameStamp()->getFrameNumber();
                FrameGenView view;
                // only needed when the post processor changed the picture; otherwise the scene texture is the same image
                if (findView(frame, view) && view.postProcessing)
                    frames->capturePreGui(frame);
            }
        };
    }

    bool frameGenRequested()
    {
        return requested();
    }

    bool frameGenWanted()
    {
        static const bool wanted = requested();
        return wanted;
    }

    void frameGenSubmitView(FrameGenView view)
    {
        std::lock_guard lock(sViewsMutex);
        sViews[view.frame % sViews.size()] = std::move(view);
    }

    osg::Camera::DrawCallback* frameGenPreGuiCapture()
    {
        static osg::ref_ptr<osg::Camera::DrawCallback> sCapture = new PreGuiCapture;
        return sCapture.get();
    }

    bool frameGenSwap(osg::GraphicsContext& gc, int width, int height, bool desktop,
        const std::function<void(int64_t presentAt)>& present)
    {
        State& s = state();
        if (!s.mTried)
        {
            s.mTried = true;
            if (!requested())
                return false;
            s.mFrames.reset(FrameGen::create(std::getenv("OPENMW_FRAMEGEN"), desktop));
        }
        FrameGen* frames = s.mFrames.get();
        if (frames == nullptr)
            return false;

        // The engine's camera and textures for this frame and the one before, when it handed them over
        const osg::State* glState = gc.getState();
        FrameGenView cur, prev;
        GLuint scene = 0, prevScene = 0, depth = 0, opaqueDepth = 0;
        bool reproject = frames->canReproject() && glState != nullptr && glState->getFrameStamp() != nullptr;
        if (reproject)
        {
            const unsigned frame = glState->getFrameStamp()->getFrameNumber();
            const unsigned contextId = glState->getContextID();
            reproject = findView(frame, cur) && findView(frame - 1, prev)
                && cur.postProcessing == prev.postProcessing;
            if (reproject)
            {
                scene = textureId(*cur.scene, contextId);
                prevScene = textureId(*prev.scene, contextId);
                depth = textureId(*cur.depth, contextId);
                opaqueDepth = textureId(*cur.opaqueDepth, contextId);
                reproject = scene != 0 && prevScene != 0 && depth != 0 && opaqueDepth != 0
                    && (!cur.postProcessing || frames->hasPreGui(frame));
            }
        }
        Ramp& ramp = s.mRamp;
        // Zink is not paced and does not ramp
        frames->setLevel(desktop ? 1 : ramp.ratio(), desktop ? frames->maxFrames() : ramp.count());
        frames->capture(width, height, reproject);
        const double periodMs = desktop ? 0.0 : SwappyGL_getRefreshPeriodNanos() / 1.0e6;
        const bool withFrames = frames->due(!desktop && frames->interval() > 0.0 && frames->interval() < periodMs * 1.5);
        if (!desktop)
            ramp.frame(Clock::now(), frames->maxFrames(), frames->resting());
        const int generated = frames->frames();
        const double gapMs = frames->interval() / (generated + 1);

        // From the camera and depth, the generated frames are predictions past the rendered one: it goes out at once, and
        // they are given the times they are to be shown, so nothing waits for them. Without (menus, loading), the motion
        // estimators show a frame between the last two rendered ones, which holds the rendered one back.
        if (withFrames && reproject)
        {
            present(0);
            const int64_t shownAt = std::chrono::duration_cast<std::chrono::nanoseconds>(
                Clock::now().time_since_epoch()).count();
            for (int i = 1; i <= generated; ++i)
            {
                if (!frames->generateReprojected(cur, prev, scene, prevScene, depth, opaqueDepth,
                        static_cast<float>(i) / (generated + 1), true))
                    break;
                if (desktop)
                    std::this_thread::sleep_for(std::chrono::microseconds(static_cast<long>(gapMs * 1000.0)));
                present(desktop ? 0 : shownAt + static_cast<int64_t>(gapMs * 1.0e6 * i));
            }
            // the back buffer has the last generated frame: what the engine draws next covers it
        }
        else if (withFrames && frames->interpolates() && frames->generate(true, 0.5f))
        {
            present(0);
            if (desktop)
                std::this_thread::sleep_for(std::chrono::microseconds(static_cast<long>(frames->interval() * 500.0)));
            frames->showCurrent();
            present(0);
        }
        else
        {
            present(0);
            if (withFrames && !frames->interpolates() && frames->generate(false, 0.5f))
                present(0);
        }
        return true;
    }
}
