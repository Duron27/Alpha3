// Render scale ([Experimental] render scale, below 1 to use it). The 3D scene is drawn smaller than the window (the
// window's own size, which [Video] resolution x and y set): OpenMW's post-processor already draws the scene into its own
// buffers and onto the screen at the end, at another size than the window's for VR, and its render size is this much of
// the window (patch 0022). The last step, onto the screen, is then made here: the scene (or what the post-processing
// chain made of it) upscaled to the window ([Experimental] render scale upscaler) and sharpened
// ([Experimental] render scale sharpening), in experimental/upscale.glsl. The GUI is drawn after it, at the window's size.
//
// Without sharpening the upscale goes straight onto the screen; with it, into a buffer the window's size first, which
// the sharpening reads.
//
// The upscaler "auto" (the default) is FSR 1 EASU unless this GPU is too slow for it: EASU's per pixel arithmetic runs
// at full precision on OpenGL ES and is paid at the window's size, which took 25 ms a frame on an Adreno 615 at
// 2560x1600 (bilinear about 1 ms). The pass is timed with GPU timer queries over the first frames, and when it takes
// longer than sAutoBudgetMs it is upscaled bilinearly from then on (the sharpening stays). "fsr" keeps EASU anyway.
#include "support.hpp"

#include "experimentalsources.hpp"

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include <glad/gles2.h>

#include <osg/FrameBufferObject>
#include <osg/GLExtensions>
#include <osg/Program>
#include <osg/Shader>
#include <osg/State>
#include <osg/Texture2D>
#include <osg/Uniform>
#include <osg/Viewport>

#include <alpha3/upstream.hpp>
#include <components/debug/debuglog.hpp>

#ifndef GL_DRAW_FRAMEBUFFER_BINDING
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#endif

namespace Alpha3
{
    namespace
    {
        enum Upscaler
        {
            Bilinear,
            Lanczos,
            Fsr,
        };

        enum Sharpening
        {
            NoSharpening,
            Rcas,
            Cas,
        };

        // auto: EASU's average GPU time a frame above this, bilinear instead
        constexpr double sAutoBudgetMs = 3.0;
        // auto: frames left alone first (shader compiles, textures loading), then the frames measured
        constexpr int sAutoWarmupFrames = 60;
        constexpr int sAutoSamples = 30;

        struct RenderScaleSettings
        {
            float mScale = 1.f;
            int mUpscaler = Fsr;
            bool mAuto = false; // FSR, measured, and bilinear when it is too slow here
            int mSharpening = Rcas;
            float mSharpness = 0.5f;
        };

        std::string lower(std::string text)
        {
            std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return std::tolower(c); });
            return text;
        }

        const RenderScaleSettings& renderScaleSettings()
        {
            static const RenderScaleSettings settings = [] {
                RenderScaleSettings s;
                const float scale = settingFloat("Experimental", "render scale", 1.f);
                s.mScale = scale > 0.f && scale < 0.995f ? std::clamp(scale, 0.25f, 1.f) : 1.f;
                const std::string upscaler = lower(setting("Experimental", "render scale upscaler", "auto"));
                s.mUpscaler = upscaler == "bilinear" ? Bilinear : upscaler == "lanczos" ? Lanczos : Fsr;
                s.mAuto = upscaler != "bilinear" && upscaler != "lanczos" && upscaler != "fsr";
                const std::string sharpening = lower(setting("Experimental", "render scale sharpening", "rcas"));
                s.mSharpening = sharpening == "none" ? NoSharpening : sharpening == "cas" ? Cas : Rcas;
                s.mSharpness = std::clamp(settingFloat("Experimental", "render scale sharpness", 0.5f), 0.f, 1.f);
                if (s.mScale < 1.f)
                {
                    static const char* const upscalers[] = { "bilinear", "Lanczos", "FSR 1 EASU" };
                    static const char* const sharpenings[] = { "none", "RCAS", "CAS" };
                    Log(Debug::Info) << "Render scale " << s.mScale << ": upscaled with " << upscalers[s.mUpscaler]
                                     << (s.mAuto ? " (auto: bilinear if it is too slow on this GPU)" : "")
                                     << ", sharpening " << sharpenings[s.mSharpening] << " (" << s.mSharpness << ")";
                }
                return s;
            }();
            return settings;
        }

        osg::ref_ptr<osg::Program> makeProgram(const std::string& defines, const char* name)
        {
            const std::string source(ExperimentalSources::upscale);
            osg::ref_ptr<osg::Program> program = new osg::Program;
            program->setName(name);
            osg::ref_ptr<osg::Shader> vertex
                = new osg::Shader(osg::Shader::VERTEX, "#version 120\n#define A3_VERTEX\n" + source);
            vertex->setName("a3_upscale.vert");
            osg::ref_ptr<osg::Shader> fragment = new osg::Shader(osg::Shader::FRAGMENT, "#version 120\n" + defines + source);
            fragment->setName("a3_upscale.frag");
            program->addShader(vertex);
            program->addShader(fragment);
            return program;
        }

        osg::ref_ptr<osg::Texture2D> makeTarget()
        {
            osg::ref_ptr<osg::Texture2D> texture = new osg::Texture2D;
            texture->setSourceFormat(GL_RGBA);
            texture->setSourceType(GL_UNSIGNED_BYTE);
            texture->setInternalFormat(GL_RGBA);
            texture->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
            texture->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);
            texture->setWrap(osg::Texture::WRAP_S, osg::Texture::CLAMP_TO_EDGE);
            texture->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);
            texture->setResizeNonPowerOfTwoHint(false);
            return texture;
        }

        // A texture to draw into and its framebuffer, made again when the size changes
        struct Target
        {
            osg::ref_ptr<osg::Texture2D> mTexture;
            osg::ref_ptr<osg::FrameBufferObject> mFbo;

            osg::FrameBufferObject& get(int width, int height)
            {
                if (!mTexture || mTexture->getTextureWidth() != width || mTexture->getTextureHeight() != height)
                {
                    mTexture = makeTarget();
                    mTexture->setTextureSize(width, height);
                    mFbo = new osg::FrameBufferObject;
                    mFbo->setAttachment(osg::Camera::COLOR_BUFFER0, osg::FrameBufferAttachment(mTexture.get()));
                }
                return *mFbo;
            }
        };

        // auto: EASU's GPU time over the first frames, then the choice. Timer queries cannot nest, so a frame where another
        // one is running (OSG's own GPU statistics) is not measured. Without timer queries EASU stays.
        class AutoUpscaler
        {
        public:
            bool deciding() const { return mDeciding; }

            void begin()
            {
                mTiming = false;
                if (!mDeciding || ++mFrames <= sAutoWarmupFrames)
                    return;
                if (GLAD_GL_EXT_disjoint_timer_query == 0)
                {
                    Log(Debug::Info) << "Render scale: no GPU timer queries, FSR 1 EASU kept (upscaler auto)";
                    mDeciding = false;
                    return;
                }
                collect();
                GLint running = 0;
                glGetQueryivEXT(GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY_EXT, &running);
                if (running != 0 || !mDeciding)
                    return;
                if (mQueries[0] == 0)
                    glGenQueriesEXT(sQueries, mQueries);
                // a query still in flight is not reused
                if (mIssued[mNext])
                    return;
                glBeginQueryEXT(GL_TIME_ELAPSED_EXT, mQueries[mNext]);
                mTiming = true;
            }

            void end()
            {
                if (!mTiming)
                    return;
                glEndQueryEXT(GL_TIME_ELAPSED_EXT);
                mIssued[mNext] = true;
                mNext = (mNext + 1) % sQueries;
                mTiming = false;
            }

            /// After the measurement: true when EASU is too slow here
            bool tooSlow() const { return mTooSlow; }

        private:
            static constexpr int sQueries = 4;

            void collect()
            {
                // a disjoint operation (a clock change) makes the results in flight meaningless
                GLint disjoint = 0;
                glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
                for (int i = 0; i < sQueries; ++i)
                {
                    if (!mIssued[i])
                        continue;
                    GLuint ready = 0;
                    glGetQueryObjectuivEXT(mQueries[i], GL_QUERY_RESULT_AVAILABLE_EXT, &ready);
                    if (!ready)
                        continue;
                    GLuint64 ns = 0;
                    glGetQueryObjectui64vEXT(mQueries[i], GL_QUERY_RESULT_EXT, &ns);
                    mIssued[i] = false;
                    if (!disjoint)
                        mSamples.push_back(ns / 1.0e6);
                }
                if (static_cast<int>(mSamples.size()) < sAutoSamples)
                    return;
                // the median: a frame that waited on something else does not decide it
                std::nth_element(mSamples.begin(), mSamples.begin() + mSamples.size() / 2, mSamples.end());
                const double ms = mSamples[mSamples.size() / 2];
                mTooSlow = ms > sAutoBudgetMs;
                mDeciding = false;
                glDeleteQueriesEXT(sQueries, mQueries);
                std::fill(std::begin(mIssued), std::end(mIssued), false);
                Log(Debug::Info) << "Render scale: FSR 1 EASU takes " << ms << " ms a frame on this GPU, "
                                 << (mTooSlow ? "over" : "within") << " the " << sAutoBudgetMs << " ms budget: "
                                 << (mTooSlow ? "upscaled bilinearly from now on (upscaler fsr keeps EASU)"
                                              : "EASU kept");
            }

            bool mDeciding = true;
            bool mTiming = false;
            bool mTooSlow = false;
            int mFrames = 0;
            int mNext = 0;
            GLuint mQueries[sQueries] = {};
            bool mIssued[sQueries] = {};
            std::vector<double> mSamples;
        };

        // Used on the draw thread only
        struct Passes
        {
            osg::ref_ptr<osg::StateSet> mUpscale = new osg::StateSet;
            osg::ref_ptr<osg::StateSet> mSharpen = new osg::StateSet;
            osg::ref_ptr<osg::Uniform> mUpscaleSize = new osg::Uniform("a3SourceSize", osg::Vec2f(1.f, 1.f));
            osg::ref_ptr<osg::Uniform> mSharpenSize = new osg::Uniform("a3SourceSize", osg::Vec2f(1.f, 1.f));
            Target mChainOutput; // at the scene's size
            Target mWide; // at the window's size, between the upscale and the sharpening
            AutoUpscaler mAuto;

            Passes()
            {
                const RenderScaleSettings& s = renderScaleSettings();
                setUpscaler(s.mUpscaler);
                mUpscale->addUniform(new osg::Uniform("a3Source", 0));
                mUpscale->addUniform(mUpscaleSize);
                if (s.mSharpening != NoSharpening)
                {
                    mSharpen->setAttributeAndModes(
                        makeProgram("#define A3_SHARPEN " + std::to_string(s.mSharpening) + "\n", "Alpha3 sharpen"));
                    mSharpen->addUniform(new osg::Uniform("a3Source", 0));
                    mSharpen->addUniform(mSharpenSize);
                    mSharpen->addUniform(new osg::Uniform("a3Sharpness", s.mSharpness));
                }
            }

            void setUpscaler(int upscaler)
            {
                mUpscale->setAttributeAndModes(
                    makeProgram("#define A3_UPSCALE " + std::to_string(upscaler) + "\n", "Alpha3 upscale"));
            }
        };

        Passes& passes()
        {
            static Passes instance;
            return instance;
        }

        void drawPass(osg::State& state, osg::StateSet& stateSet, osg::Texture& source, const std::function<void()>& draw)
        {
            state.pushStateSet(&stateSet);
            state.apply();
            state.applyTextureAttribute(0, &source);
            draw();
            state.popStateSet();
            state.apply();
        }
    }

    float renderScale()
    {
        return renderScaleSettings().mScale;
    }

    int renderScaled(int windowSize)
    {
        const float scale = renderScale();
        if (scale >= 1.f)
            return windowSize;
        return std::max(1, static_cast<int>(std::lround(windowSize * scale)));
    }

    osg::FrameBufferObject& renderScaleChainOutput(int width, int height)
    {
        return passes().mChainOutput.get(width, height);
    }

    osg::Texture* renderScaleChainOutputTexture()
    {
        return passes().mChainOutput.mTexture.get();
    }

    void renderScaleResolve(
        osg::State& state, osg::Texture& source, const osg::Viewport& screen, const std::function<void()>& draw)
    {
        Passes& p = passes();
        const bool sharpen = renderScaleSettings().mSharpening != NoSharpening;
        const int width = static_cast<int>(screen.width());
        const int height = static_cast<int>(screen.height());
        GLint destination = 0;
        if (sharpen)
        {
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &destination);
            p.mWide.get(width, height).apply(state, osg::FrameBufferObject::DRAW_FRAMEBUFFER);
            glViewport(0, 0, width, height);
            state.haveAppliedAttribute(osg::StateAttribute::VIEWPORT);
        }
        else
            screen.apply(state);

        p.mUpscaleSize->set(osg::Vec2f(source.getTextureWidth(), source.getTextureHeight()));
        const bool measuring = renderScaleSettings().mAuto && p.mAuto.deciding();
        if (measuring)
            p.mAuto.begin();
        drawPass(state, *p.mUpscale, source, draw);
        if (measuring)
        {
            p.mAuto.end();
            if (!p.mAuto.deciding() && p.mAuto.tooSlow())
                p.setUpscaler(Bilinear);
        }

        if (sharpen)
        {
            state.get<osg::GLExtensions>()->glBindFramebuffer(
                GL_DRAW_FRAMEBUFFER_EXT, static_cast<GLuint>(destination));
            screen.apply(state);
            p.mSharpenSize->set(osg::Vec2f(width, height));
            drawPass(state, *p.mSharpen, *p.mWide.mTexture, draw);
        }
    }
}
