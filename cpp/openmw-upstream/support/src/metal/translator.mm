// The frame OSG culled, drawn with Metal (iOS; src/vulkan/translator.cpp is Android's Vulkan renderer, which this follows
// part for part). The render stages are walked as RenderStage::draw does (pre-render stages, the stage, post-render
// stages), and their bins as RenderBin::drawImplementation does; each leaf's state is the StateSets along its StateGraph
// (the bins' own after the root's), applied with OSG's rules for OVERRIDE and PROTECTED. From that state come the program
// (a pipeline per fixed function state and vertex layout), its uniforms (a uniform block per stage, filled from OSG's
// uniforms by name) and its textures (by the unit its sampler uniform names).
//
// A camera that draws into textures (an FBO) gets textures made for them, which shaders then sample. Render targets are
// drawn the OpenGL way up (row 0 at the bottom: the vertex stage turns y over, as Metal's rows go down), so that the
// shaders' texture coordinates and gl_FragCoord read them as they do under OpenGL ES; the window is drawn as it is (its
// row 0 at the top), its viewport and scissor rectangles turned over instead.
//
// Drawables are osg::Geometry and its kind. The engine's own drawables that draw with OpenGL calls have stand-ins here:
// MyGUI's batches and the post-processing canvas (which shows the scene's texture). Those the translator does not know
// are left out (logged once each).
#include "translator.hpp"

#include "context.hpp"
#include "programs.hpp"
#include "resources.hpp"

#include "../framegenlock.hpp"
#include "../support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <typeinfo>
#include <unordered_map>
#include <unordered_set>

#include <osg/BlendColor>
#include <osg/BufferIndexBinding>
#include <osg/DispatchCompute>
#include <osg/BlendEquation>
#include <osg/BlendFunc>
#include <osg/ClipControl>
#include <osg/ClipPlane>
#include <osg/ColorMask>
#include <osg/ColorMaski>
#include <osg/CullFace>
#include <osg/Depth>
#include <osg/FrameBufferObject>
#include <osg/FrontFace>
#include <osg/Geometry>
#include <osg/Image>
#include <osg/PolygonOffset>
#include <osg/Program>
#include <osg/Quat>
#include <osg/Stats>
#include <osg/Stencil>
#include <osg/Texture>
#include <osg/Texture2D>
#include <osg/Uniform>
#include <osgUtil/RenderStage>
#include <osgUtil/SceneView>
#include <osgUtil/StateGraph>
#include <osgViewer/View>
#include <osgViewer/ViewerBase>
#include <osgViewer/ViewerEventHandlers>
#include <osgParticle/ParticleSystem>
#include <osgText/Text>

#include <components/resource/resourcesystem.hpp>
#include <components/resource/scenemanager.hpp>
#include <components/shader/shadermanager.hpp>
#include <components/terrain/compositemaprenderer.hpp>

#include <components/debug/debuglog.hpp>
#include <components/fx/technique.hpp>
#include <components/fx/types.hpp>
#include <components/sceneutil/depth.hpp>

namespace Alpha3::Mtl
{
    namespace
    {
        using AttributeMap
            = std::map<osg::StateAttribute::TypeMemberPair, std::pair<const osg::StateAttribute*, unsigned>>;

        // OSG's own layouts of two engine classes the translator reads (their members are private and their headers
        // are the engine's): MyGUI's drawable (components/myguiplatform/myguirendermanager.cpp) and the shader
        // manager's program, which holds its samplers' units (components/shader/shadermanager.cpp)
        struct MyGuiBatch
        {
            osg::ref_ptr<osg::Texture2D> mTexture;
            osg::ref_ptr<osg::VertexBufferObject> mVertexBuffer;
            osg::ref_ptr<osg::Array> mArray;
            osg::ref_ptr<osg::StateSet> mStateSet;
            size_t mVertexCount;
        };

        class MyGuiDrawable : public osg::Drawable
        {
        public:
            void* mParent;
            osg::ref_ptr<osg::StateSet> mStateSet;
            std::vector<MyGuiBatch> mBatchVector[4];
            int mWriteTo;
            mutable int mReadFrom;
            osg::ref_ptr<osg::Texture2D> mDummyTexture;
        };

        // apps/openmw/mwgui/loadingscreen.cpp: the loading screen's background is the last frame, which this callback
        // copies (glCopyTexImage2D) into its texture when the main camera draws
        class CopyFramebufferCallback : public osg::Camera::DrawCallback
        {
        public:
            mutable bool mOneshot;
            osg::ref_ptr<osg::Texture2D> mTexture;
        };

        // apps/openmw/mwrender/screenshotmanager.cpp: a drawable whose draw reads the window into an image (a saved
        // game's screenshot), while the main thread waits for the camera's final draw callback
        class ReadImageCallback : public osg::Drawable::DrawCallback
        {
        public:
            int mWidth;
            int mHeight;
            osg::ref_ptr<osg::Image> mImage;
        };

        // apps/openmw/mwrender/ripples.hpp: the water's ripple map, simulated by its draw (two passes between two
        // textures, compute or fragment shaders) inside a camera of its own that has no attachments
        class RipplesSurfaceMirror : public osg::Geometry
        {
        public:
            struct State
            {
                bool mPaused;
                bool mClear;
                osg::ref_ptr<osg::StateSet> mStateset;
            };
            Resource::ResourceSystem* mResourceSystem;
            size_t mPositionCount;
            std::array<osg::Vec3f, 100> mPositions;
            std::array<State, 2> mState;
            osg::Vec2f mCurrentPlayerPos;
            osg::Vec2f mLastPlayerPos;
            std::array<osg::ref_ptr<osg::Texture2D>, 2> mTextures;
        };

        class RipplesCameraMirror : public osg::Camera
        {
        public:
            osg::ref_ptr<osg::Geometry> mRipples;
        };

        // apps/openmw/mwrender/opaqueblit.hpp: after the opaque bins, the scene's colour and depth copied into the
        // textures the water, soft particles and distortion read (opaqueTexColorMap, opaqueTexMap)
        class OpaqueColorBinCallbackMirror : public osgUtil::RenderBin::DrawCallback
        {
        public:
            osg::ref_ptr<osg::FrameBufferObject> mFbo[2];
            osg::ref_ptr<osg::FrameBufferObject> mMsaaFbo[2];
            osg::ref_ptr<osg::FrameBufferObject> mOpaqueFbo[2];
        };

        class SamplerProgram : public osg::Program
        {
        public:
            std::vector<osg::ref_ptr<osg::Uniform>> mSamplers;
        };

        // components/terrain/compositemaprenderer.hpp, whose queues are private
        class CompositeMapQueues : public osg::Drawable
        {
        public:
            float mTargetFrameRate;
            double mMinimumTimeAvailable;
            mutable osg::Timer mTimer;
            mutable std::set<std::shared_ptr<Terrain::CompositeMap>> mCompileSet;
            mutable std::set<std::shared_ptr<Terrain::CompositeMap>> mImmediateCompileSet;
            mutable std::mutex mMutex;
            osg::ref_ptr<osg::FrameBufferObject> mFBO;
        };

        // apps/openmw/mwrender/pingpongcanvas.hpp: the post processing chain, which its draw runs (the techniques'
        // passes between three textures, the last into the window)
        class PingPongCanvasMirror : public osg::Geometry
        {
        public:
            bool mAvgLum;
            bool mPostprocessing;
            Fx::DispatchArray mPasses;
            Fx::FlagsType mMask;
            osg::ref_ptr<osg::Program> mFallbackProgram;
            osg::ref_ptr<osg::Program> mMultiviewResolveProgram;
            osg::ref_ptr<osg::StateSet> mFallbackStateSet;
            osg::ref_ptr<osg::StateSet> mMultiviewResolveStateSet;
            osg::ref_ptr<osg::Texture> mTextureScene;
            osg::ref_ptr<osg::Texture> mTextureDepth;
            osg::ref_ptr<osg::Texture> mTextureNormals;
            osg::ref_ptr<osg::Texture> mTextureDistortion;
            mutable bool mDirty;
            mutable std::vector<Fx::Types::RenderTarget> mDirtyAttachments;
        };

        // osgParticle's frame bookkeeping, which its updaters read to tell whether a system was drawn lately
        class ParticleAccess : public osgParticle::ParticleSystem
        {
        public:
            static void drawn(const osgParticle::ParticleSystem& system, unsigned frame)
            {
                const auto& access = static_cast<const ParticleAccess&>(system);
                access._last_frame = frame;
                access._dirty_dt = true;
            }
        };

        struct Accumulated;

        struct DrawTemplate
        {
            Variant* mVariant = nullptr;
            // the vertex and fragment stage's uniforms (but for the per draw ones) and the light list's
            std::vector<uint8_t> mBlocks[3];
            struct Patch
            {
                int mStage;
                uint32_t mOffset;
                int mWhich; // 0 model view, 1 projection, 2 model view projection, 3 normal
            };
            std::vector<Patch> mPatches;
            // the samplers' elements in the variant's order: their textures and samplers
            struct Bound
            {
                id<MTLTexture> mTexture = nil;
                id<MTLSamplerState> mSampler = nil;
            };
            std::vector<Bound> mImages;

            // What the blocks and images are made from again each frame (and for each target): the uniforms' values,
            // over the initializers' (mBase), and the textures of the samplers' elements
            std::vector<uint8_t> mBase[3];
            struct Source
            {
                int mStage;
                const UniformMember* mMember;
                const osg::Uniform* mUniform;
                uint32_t mLevel;
                const Accumulated* mOwner;
            };
            std::vector<Source> mSources;
            std::vector<const Source*> mVolatile; // the sources read for each draw
            struct ImageSource
            {
                const osg::Texture* mTexture;
                bool mShadow;
                VkImageViewType mViewType;
            };
            std::vector<ImageSource> mImageSources;
            uint64_t mFrame = ~0ull;
            const void* mTargetColor = nullptr;
            const void* mTargetDepth = nullptr;
            // the blocks of a draw that needs nothing patched, once per frame
            id<MTLBuffer> mStatic[3] = { nil, nil, nil };
            uint64_t mStaticOffset[3]{};
            // the sources' modified counts mBlocks was written with: they are written again only when one changes
            std::vector<unsigned> mStamps;
            bool mWritten = false;

            DrawTemplate() = default;
            DrawTemplate(const DrawTemplate&) = delete;
            DrawTemplate& operator=(const DrawTemplate&) = delete;
        };

        struct Accumulated
        {
            AttributeMap mAttributes;
            std::map<unsigned, unsigned> mModes;
            std::vector<AttributeMap> mTextures;
            struct UniformRef
            {
                const osg::Uniform* mUniform = nullptr;
                unsigned mOverride = 0;
                uint32_t mLevel = 0; // which of the state sets put together gave it (0 the first)
                const Accumulated* mOwner = nullptr; // the state made by that state set
            };
            std::map<std::string, UniformRef> mUniforms;
            osg::StateSet::DefineList mDefines;

            uint64_t mId = 0; // a number no other state has had: what states made from this one are found by
            uint64_t mDerivedFrame = ~0ull;
            uint32_t mDepth = 0; // how many state sets were put together for it
            uint32_t mClipModes = 0; // GL_CLIP_PLANE0 and 1 on (bits 0 and 1), derived
            // its last state set's uniforms are other objects from draw to draw (OpenMW's light lists, made each frame):
            // they are read from the draw's own state sets
            bool mVolatile = false;
            const osg::Program* mProgram = nullptr;
            const osg::Program* mVariantProgram = nullptr;
            Variant* mVariant = nullptr;
            uint64_t mVariantGeneration = 0; // Programs::sourceGeneration when it was looked up
            PipelineState mFixed;
            bool mGlCounterClockwise = true;
            float mMinDepth = 0.f, mMaxDepth = 1.f;
            uint32_t mStencilReference = 0;
            float mBlendConstants[4]{};
            std::vector<std::unique_ptr<DrawTemplate>> mTemplates;

            template <class Map, class Key, class Value>
            static void push(Map& map, const Key& key, const Value& value, unsigned override)
            {
                auto found = map.find(key);
                if (found != map.end() && (found->second.second & osg::StateAttribute::OVERRIDE)
                    && !(override & osg::StateAttribute::PROTECTED))
                    return;
                map[key] = { value, override };
            }

            void apply(const osg::StateSet* stateSet)
            {
                if (stateSet == nullptr)
                    return;
                for (const auto& [key, ref] : stateSet->getAttributeList())
                    push(mAttributes, key, static_cast<const osg::StateAttribute*>(ref.first.get()), ref.second);
                for (const auto& [mode, value] : stateSet->getModeList())
                {
                    auto found = mModes.find(mode);
                    if (found != mModes.end() && (found->second & osg::StateAttribute::OVERRIDE)
                        && !(value & osg::StateAttribute::PROTECTED))
                        continue;
                    mModes[mode] = value;
                }
                const auto& textures = stateSet->getTextureAttributeList();
                if (textures.size() > mTextures.size())
                    mTextures.resize(textures.size());
                for (size_t unit = 0; unit < textures.size(); ++unit)
                    for (const auto& [key, ref] : textures[unit])
                        push(mTextures[unit], key, static_cast<const osg::StateAttribute*>(ref.first.get()),
                            ref.second);
                for (const auto& [name, ref] : stateSet->getUniformList())
                {
                    auto found = mUniforms.find(name);
                    if (found != mUniforms.end() && (found->second.mOverride & osg::StateAttribute::OVERRIDE)
                        && !(ref.second & osg::StateAttribute::PROTECTED))
                        continue;
                    mUniforms[name] = { static_cast<const osg::Uniform*>(ref.first.get()), ref.second, mDepth - 1, this };
                }
                for (const auto& [name, define] : stateSet->getDefineList())
                {
                    auto found = mDefines.find(name);
                    if (found != mDefines.end() && (found->second.second & osg::StateAttribute::OVERRIDE)
                        && !(define.second & osg::StateAttribute::PROTECTED))
                        continue;
                    mDefines[name] = define;
                }
            }

            // A copy without the derived parts
            std::shared_ptr<Accumulated> copy() const
            {
                auto out = std::make_shared<Accumulated>();
                out->mAttributes = mAttributes;
                out->mModes = mModes;
                out->mTextures = mTextures;
                out->mUniforms = mUniforms;
                out->mDefines = mDefines;
                return out;
            }

            const osg::StateAttribute* attribute(osg::StateAttribute::Type type, unsigned member = 0) const
            {
                auto found = mAttributes.find({ type, member });
                return found == mAttributes.end() ? nullptr : found->second.first;
            }

            bool mode(unsigned mode, bool fallback) const
            {
                auto found = mModes.find(mode);
                return found == mModes.end() ? fallback : (found->second & osg::StateAttribute::ON) != 0;
            }

            const osg::Texture* texture(unsigned unit) const
            {
                if (unit >= mTextures.size())
                    return nullptr;
                auto found = mTextures[unit].find({ osg::StateAttribute::TEXTURE, 0 });
                // a TEXTURE attribute is an osg::Texture
                return found == mTextures[unit].end() ? nullptr
                                                      : static_cast<const osg::Texture*>(found->second.first);
            }

            const osg::Uniform* uniform(const std::string& name) const
            {
                auto found = mUniforms.find(name);
                return found == mUniforms.end() ? nullptr : found->second.mUniform;
            }
        };

        VkBlendFactor blendFactor(int gl)
        {
            switch (gl)
            {
                case 0:
                    return VK_BLEND_FACTOR_ZERO;
                case 1:
                    return VK_BLEND_FACTOR_ONE;
                case 0x300:
                    return VK_BLEND_FACTOR_SRC_COLOR;
                case 0x301:
                    return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                case 0x302:
                    return VK_BLEND_FACTOR_SRC_ALPHA;
                case 0x303:
                    return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                case 0x304:
                    return VK_BLEND_FACTOR_DST_ALPHA;
                case 0x305:
                    return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
                case 0x306:
                    return VK_BLEND_FACTOR_DST_COLOR;
                case 0x307:
                    return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                case 0x308:
                    return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
                case 0x8001:
                    return VK_BLEND_FACTOR_CONSTANT_COLOR;
                case 0x8002:
                    return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
                case 0x8003:
                    return VK_BLEND_FACTOR_CONSTANT_ALPHA;
                case 0x8004:
                    return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
                default:
                    return VK_BLEND_FACTOR_ONE;
            }
        }

        VkBlendOp blendOp(int gl)
        {
            switch (gl)
            {
                case 0x800A:
                    return VK_BLEND_OP_SUBTRACT;
                case 0x800B:
                    return VK_BLEND_OP_REVERSE_SUBTRACT;
                case 0x8007:
                    return VK_BLEND_OP_MIN;
                case 0x8008:
                    return VK_BLEND_OP_MAX;
                default:
                    return VK_BLEND_OP_ADD;
            }
        }

        VkCompareOp compare(unsigned gl)
        {
            return gl >= 0x200 && gl <= 0x207 ? static_cast<VkCompareOp>(gl - 0x200) : VK_COMPARE_OP_ALWAYS;
        }

        // as SceneUtil::AutoDepth::getReversedDepthFunction
        uint32_t reversedCompare(uint32_t op)
        {
            switch (op)
            {
                case VK_COMPARE_OP_LESS:
                    return VK_COMPARE_OP_GREATER;
                case VK_COMPARE_OP_LESS_OR_EQUAL:
                    return VK_COMPARE_OP_GREATER_OR_EQUAL;
                case VK_COMPARE_OP_GREATER:
                    return VK_COMPARE_OP_LESS;
                case VK_COMPARE_OP_GREATER_OR_EQUAL:
                    return VK_COMPARE_OP_LESS_OR_EQUAL;
                default:
                    return op;
            }
        }

        VkStencilOp stencilOp(unsigned gl)
        {
            switch (gl)
            {
                case 0:
                    return VK_STENCIL_OP_ZERO;
                case 0x1E01:
                    return VK_STENCIL_OP_REPLACE;
                case 0x1E02:
                    return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
                case 0x1E03:
                    return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
                case 0x150A:
                    return VK_STENCIL_OP_INVERT;
                case 0x8507:
                    return VK_STENCIL_OP_INCREMENT_AND_WRAP;
                case 0x8508:
                    return VK_STENCIL_OP_DECREMENT_AND_WRAP;
                default:
                    return VK_STENCIL_OP_KEEP;
            }
        }

        // The fixed function state of a leaf, as OpenGL would have it (the parts that depend on the target and the
        // primitives are filled in when it is drawn)
        // The state's program and fixed function state, from its attributes (again each frame: they change in place)
        void derive(Accumulated& state, uint64_t frame)
        {
            if (state.mDerivedFrame == frame)
                return;
            state.mDerivedFrame = frame;
            state.mFixed = PipelineState{};
            state.mGlCounterClockwise = true;
            state.mMinDepth = 0.f;
            state.mMaxDepth = 1.f;
            state.mStencilReference = 0;
            state.mClipModes = (state.mode(0x3000, false) ? 1u : 0u) | (state.mode(0x3001, false) ? 2u : 0u);
            for (float& constant : state.mBlendConstants)
                constant = 0.f;
            state.mProgram = static_cast<const osg::Program*>(state.attribute(osg::StateAttribute::PROGRAM));
            PipelineState& fixed = state.mFixed;

            fixed.mDepthTest = state.mode(0x0B71, false); // GL_DEPTH_TEST
            fixed.mDepthWrite = 1;
            fixed.mDepthCompare = VK_COMPARE_OP_LESS;
            if (auto* depth = static_cast<const osg::Depth*>(state.attribute(osg::StateAttribute::DEPTH)))
            {
                fixed.mDepthCompare = compare(depth->getFunction());
                // OpenMW's AutoDepth applies the reversed function when the depth is reversed
                if (SceneUtil::AutoDepth::isReversed() && dynamic_cast<const SceneUtil::AutoDepth*>(depth) != nullptr)
                    fixed.mDepthCompare = reversedCompare(fixed.mDepthCompare);
                fixed.mDepthWrite = depth->getWriteMask() ? 1 : 0;
                state.mMinDepth = static_cast<float>(depth->getZNear());
                state.mMaxDepth = static_cast<float>(depth->getZFar());
            }
            // OpenGL writes no depth without the test
            if (!fixed.mDepthTest)
            {
                fixed.mDepthWrite = 0;
                fixed.mDepthCompare = VK_COMPARE_OP_ALWAYS;
            }

            fixed.mBlend = state.mode(0x0BE2, false); // GL_BLEND
            if (auto* func = static_cast<const osg::BlendFunc*>(state.attribute(osg::StateAttribute::BLENDFUNC)))
            {
                fixed.mSrcColor = blendFactor(func->getSourceRGB());
                fixed.mDstColor = blendFactor(func->getDestinationRGB());
                fixed.mSrcAlpha = blendFactor(func->getSourceAlpha());
                fixed.mDstAlpha = blendFactor(func->getDestinationAlpha());
            }
            if (auto* equation
                = static_cast<const osg::BlendEquation*>(state.attribute(osg::StateAttribute::BLENDEQUATION)))
            {
                fixed.mColorOp = blendOp(equation->getEquationRGB());
                fixed.mAlphaOp = blendOp(equation->getEquationAlpha());
            }
            if (auto* color = static_cast<const osg::BlendColor*>(state.attribute(osg::StateAttribute::BLENDCOLOR)))
                for (int i = 0; i < 4; ++i)
                    state.mBlendConstants[i] = color->getConstantColor()[i];
            if (!fixed.mBlend)
            {
                fixed.mSrcColor = fixed.mSrcAlpha = VK_BLEND_FACTOR_ONE;
                fixed.mDstColor = fixed.mDstAlpha = VK_BLEND_FACTOR_ZERO;
                fixed.mColorOp = fixed.mAlphaOp = VK_BLEND_OP_ADD;
            }

            if (auto* mask = static_cast<const osg::ColorMask*>(state.attribute(osg::StateAttribute::COLORMASK)))
                fixed.mWriteMask = (mask->getRedMask() ? 1 : 0) | (mask->getGreenMask() ? 2 : 0)
                    | (mask->getBlueMask() ? 4 : 0) | (mask->getAlphaMask() ? 8 : 0);
            // glColorMask is every draw buffer's; glColorMaski(1) the second's (OpenMW's normals: left unwritten by
            // what has none)
            fixed.mWriteMask1 = fixed.mWriteMask;
            if (auto* mask = static_cast<const osg::ColorMaski*>(state.attribute(osg::StateAttribute::COLORMASK, 1)))
                fixed.mWriteMask1 = (mask->getRedMask() ? 1 : 0) | (mask->getGreenMask() ? 2 : 0)
                    | (mask->getBlueMask() ? 4 : 0) | (mask->getAlphaMask() ? 8 : 0);

            fixed.mCullMode = VK_CULL_MODE_NONE;
            if (state.mode(0x0B44, false)) // GL_CULL_FACE
            {
                fixed.mCullMode = VK_CULL_MODE_BACK_BIT;
                if (auto* cull = static_cast<const osg::CullFace*>(state.attribute(osg::StateAttribute::CULLFACE)))
                    fixed.mCullMode = cull->getMode() == osg::CullFace::FRONT ? VK_CULL_MODE_FRONT_BIT
                        : cull->getMode() == osg::CullFace::FRONT_AND_BACK    ? VK_CULL_MODE_FRONT_AND_BACK
                                                                              : VK_CULL_MODE_BACK_BIT;
            }
            if (auto* front = static_cast<const osg::FrontFace*>(state.attribute(osg::StateAttribute::FRONTFACE)))
                state.mGlCounterClockwise = front->getMode() == osg::FrontFace::COUNTER_CLOCKWISE;

            // the shadow casters: in front of the shadow camera's near plane they are drawn at it, not cut away
            fixed.mDepthClamp = state.mode(0x864F, false); // GL_DEPTH_CLAMP
            if (auto* clip = static_cast<const osg::ClipControl*>(state.attribute(osg::StateAttribute::CLIPCONTROL)))
                fixed.mClipZeroToOne = clip->getDepthMode() == osg::ClipControl::ZERO_TO_ONE ? 1 : 0;
            if (state.mode(0x8037, false)) // GL_POLYGON_OFFSET_FILL
                if (auto* offset
                    = static_cast<const osg::PolygonOffset*>(state.attribute(osg::StateAttribute::POLYGONOFFSET)))
                {
                    fixed.mDepthBias = 1;
                    fixed.mBiasConstant = offset->getUnits();
                    fixed.mBiasSlope = offset->getFactor();
                }

            if (state.mode(0x0B90, false)) // GL_STENCIL_TEST
                if (auto* stencil = static_cast<const osg::Stencil*>(state.attribute(osg::StateAttribute::STENCIL)))
                {
                    fixed.mStencil = 1;
                    fixed.mStencilCompare = compare(stencil->getFunction());
                    fixed.mStencilReadMask = stencil->getFunctionMask();
                    fixed.mStencilWriteMask = stencil->getWriteMask();
                    fixed.mStencilFail = stencilOp(stencil->getStencilFailOperation());
                    fixed.mStencilDepthFail = stencilOp(stencil->getStencilPassAndDepthFailOperation());
                    fixed.mStencilPass = stencilOp(stencil->getStencilPassAndDepthPassOperation());
                    state.mStencilReference = static_cast<uint32_t>(stencil->getFunctionRef());
                }
        }

        struct Shape
        {
            uint32_t mColumns = 0, mRows = 0;
            char mBase = 'f';
        };

        Shape shapeOf(int glType)
        {
            switch (glType)
            {
                case 0x1406:
                    return { 1, 1, 'f' };
                case 0x8B50:
                    return { 1, 2, 'f' };
                case 0x8B51:
                    return { 1, 3, 'f' };
                case 0x8B52:
                    return { 1, 4, 'f' };
                case 0x1404:
                    return { 1, 1, 'i' };
                case 0x8B53:
                    return { 1, 2, 'i' };
                case 0x8B54:
                    return { 1, 3, 'i' };
                case 0x8B55:
                    return { 1, 4, 'i' };
                case 0x1405:
                    return { 1, 1, 'u' };
                case 0x8DC6:
                    return { 1, 2, 'u' };
                case 0x8DC7:
                    return { 1, 3, 'u' };
                case 0x8DC8:
                    return { 1, 4, 'u' };
                case 0x8B56:
                    return { 1, 1, 'b' };
                case 0x8B57:
                    return { 1, 2, 'b' };
                case 0x8B58:
                    return { 1, 3, 'b' };
                case 0x8B59:
                    return { 1, 4, 'b' };
                case 0x8B5A:
                    return { 2, 2, 'f' };
                case 0x8B5B:
                    return { 3, 3, 'f' };
                case 0x8B5C:
                    return { 4, 4, 'f' };
                case 0x8B65:
                    return { 2, 3, 'f' };
                case 0x8B66:
                    return { 2, 4, 'f' };
                case 0x8B67:
                    return { 3, 2, 'f' };
                case 0x8B68:
                    return { 3, 4, 'f' };
                case 0x8B69:
                    return { 4, 2, 'f' };
                case 0x8B6A:
                    return { 4, 3, 'f' };
                default:
                    return {};
            }
        }

        // Writes values (a column after another, as OpenGL takes a matrix) into a member of a uniform block
        template <class Get>
        void writeMember(uint8_t* block, uint32_t blockSize, const UniformMember& member, uint32_t elements,
            uint32_t componentsPerElement, Get get)
        {
            const Shape shape = shapeOf(member.mGlType);
            if (shape.mColumns == 0)
                return;
            const uint32_t stride = member.mArrayStride != 0 ? member.mArrayStride
                                                             : (shape.mColumns > 1 ? shape.mColumns * 16 : 16);
            elements = std::min(elements, member.mArraySize);
            for (uint32_t e = 0; e < elements; ++e)
                for (uint32_t c = 0; c < shape.mColumns; ++c)
                    for (uint32_t r = 0; r < shape.mRows; ++r)
                    {
                        const uint32_t index = e * componentsPerElement + c * shape.mRows + r;
                        const uint32_t at = member.mOffset + e * stride + (shape.mColumns > 1 ? c * 16 : 0) + r * 4;
                        if (at + 4 > blockSize)
                            return;
                        const double value = get(index);
                        uint8_t* out = block + at;
                        switch (shape.mBase)
                        {
                            case 'f':
                            {
                                const float f = static_cast<float>(value);
                                std::memcpy(out, &f, 4);
                                break;
                            }
                            case 'i':
                            {
                                const int32_t i = static_cast<int32_t>(value);
                                std::memcpy(out, &i, 4);
                                break;
                            }
                            default:
                            {
                                const uint32_t u = shape.mBase == 'b' ? (value != 0 ? 1u : 0u)
                                                                      : static_cast<uint32_t>(value);
                                std::memcpy(out, &u, 4);
                                break;
                            }
                        }
                    }
        }

        void writeUniform(uint8_t* block, uint32_t blockSize, const UniformMember& member, const osg::Uniform& uniform)
        {
            const unsigned components = osg::Uniform::getTypeNumComponents(uniform.getType());
            if (components == 0)
                return;
            const osg::FloatArray* floats = uniform.getFloatArray();
            // floats into a float member (vectors, matrices and the light lists' arrays, nearly all of them): a column
            // at a time, without a conversion per value
            if (floats != nullptr)
            {
                const Shape shape = shapeOf(member.mGlType);
                if (shape.mBase == 'f' && shape.mColumns * shape.mRows == components)
                {
                    const uint32_t stride = member.mArrayStride != 0
                        ? member.mArrayStride
                        : (shape.mColumns > 1 ? shape.mColumns * 16 : 16);
                    const uint32_t elements = std::min<uint32_t>(uniform.getNumElements(), member.mArraySize);
                    const uint32_t columnBytes = shape.mRows * 4;
                    const float* data = floats->empty() ? nullptr : &floats->front();
                    const size_t available = floats->size();
                    // vec4s and mat4s (and arrays of them) are laid out as OSG holds them: one copy
                    if (shape.mRows == 4 && stride == shape.mColumns * 16)
                    {
                        const size_t count = std::min<size_t>(static_cast<size_t>(elements) * components, available);
                        const size_t bytes = std::min<size_t>(count * 4, blockSize > member.mOffset ? blockSize - member.mOffset : 0);
                        if (data != nullptr && bytes > 0)
                            std::memcpy(block + member.mOffset, data, bytes / 16 * 16);
                        return;
                    }
                    for (uint32_t e = 0; e < elements; ++e)
                        for (uint32_t c = 0; c < shape.mColumns; ++c)
                        {
                            const size_t index = static_cast<size_t>(e) * components + c * shape.mRows;
                            const uint32_t at = member.mOffset + e * stride + (shape.mColumns > 1 ? c * 16 : 0);
                            if (at + columnBytes > blockSize || index + shape.mRows > available)
                                return;
                            std::memcpy(block + at, data + index, columnBytes);
                        }
                    return;
                }
            }
            const osg::DoubleArray* doubles = uniform.getDoubleArray();
            const osg::IntArray* ints = uniform.getIntArray();
            const osg::UIntArray* uints = uniform.getUIntArray();
            const size_t total = floats ? floats->size()
                : doubles               ? doubles->size()
                : ints                  ? ints->size()
                : uints                 ? uints->size()
                                        : 0;
            writeMember(block, blockSize, member, uniform.getNumElements(), components, [&](uint32_t index) -> double {
                if (index >= total)
                    return 0.0;
                if (floats)
                    return (*floats)[index];
                if (doubles)
                    return (*doubles)[index];
                if (ints)
                    return (*ints)[index];
                return (*uints)[index];
            });
        }

        // a3DrawUniforms: three mat4, a mat3, two clip planes and the model view's inverse (filled only for the programs
        // that read it), std140
        constexpr uint32_t sDrawBlockSize = 336;

        int builtinIndex(const std::string& name)
        {
            if (name == "osg_ModelViewMatrix")
                return 0;
            if (name == "osg_ProjectionMatrix")
                return 1;
            if (name == "osg_ModelViewProjectionMatrix")
                return 2;
            if (name == "osg_NormalMatrix")
                return 3;
            return -1;
        }

        VkPrimitiveTopology topologyOf(unsigned mode, bool& convert)
        {
            convert = false;
            switch (mode)
            {
                case 0:
                    return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
                case 1:
                    return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
                case 2: // GL_LINE_LOOP: a strip that comes back to its start
                    convert = true;
                    return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
                case 3:
                    return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
                case 5:
                case 8: // GL_QUAD_STRIP: the same vertices as a triangle strip
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
                case 6:
                case 9: // GL_POLYGON (convex): a fan, as triangles (Metal has no fans)
                    convert = true;
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                case 7: // GL_QUADS: two triangles each
                    convert = true;
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                default:
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            }
        }

        // A list of quads, a fan or a line loop as the indices of triangles or of a strip
        void convertIndices(unsigned mode, const std::vector<uint32_t>& in, std::vector<uint32_t>& out)
        {
            if (mode == 6 || mode == 9)
            {
                for (size_t i = 1; i + 1 < in.size(); ++i)
                    for (size_t at : { size_t(0), i, i + 1 })
                        out.push_back(in[at]);
            }
            else if (mode == 7)
            {
                for (size_t q = 0; q + 3 < in.size(); q += 4)
                    for (size_t i : { 0, 1, 2, 0, 2, 3 })
                        out.push_back(in[q + i]);
            }
            else if (mode == 2)
            {
                out = in;
                if (!in.empty())
                    out.push_back(in.front());
            }
            else
                out = in;
        }

        // normalized: OSG's State passes GL_TRUE for the colours and normals whatever the array says
        VkFormat vertexFormat(const osg::Array& array, bool integer, bool normalized)
        {
            const unsigned size = array.getDataSize();
            if (size < 1 || size > 4)
                return VK_FORMAT_UNDEFINED;
            const bool normalize = normalized || array.getNormalize();
            switch (array.getDataType())
            {
                case 0x1406: // GL_FLOAT
                {
                    const VkFormat formats[] = { VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT,
                        VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT };
                    return formats[size - 1];
                }
                case 0x1401: // GL_UNSIGNED_BYTE
                {
                    const VkFormat unorm[]
                        = { VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM };
                    const VkFormat uint[]
                        = { VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT };
                    const VkFormat scaled[] = { VK_FORMAT_R8_USCALED, VK_FORMAT_R8G8_USCALED,
                        VK_FORMAT_R8G8B8_USCALED, VK_FORMAT_R8G8B8A8_USCALED };
                    return integer ? uint[size - 1] : normalize ? unorm[size - 1] : scaled[size - 1];
                }
                case 0x1400: // GL_BYTE
                {
                    const VkFormat snorm[]
                        = { VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8_SNORM, VK_FORMAT_R8G8B8A8_SNORM };
                    const VkFormat sint[]
                        = { VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8_SINT, VK_FORMAT_R8G8B8A8_SINT };
                    const VkFormat scaled[] = { VK_FORMAT_R8_SSCALED, VK_FORMAT_R8G8_SSCALED,
                        VK_FORMAT_R8G8B8_SSCALED, VK_FORMAT_R8G8B8A8_SSCALED };
                    return integer ? sint[size - 1] : normalize ? snorm[size - 1] : scaled[size - 1];
                }
                case 0x1403: // GL_UNSIGNED_SHORT
                {
                    const VkFormat unorm[] = { VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM,
                        VK_FORMAT_R16G16B16A16_UNORM };
                    const VkFormat uint[] = { VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT,
                        VK_FORMAT_R16G16B16A16_UINT };
                    const VkFormat scaled[] = { VK_FORMAT_R16_USCALED, VK_FORMAT_R16G16_USCALED,
                        VK_FORMAT_R16G16B16_USCALED, VK_FORMAT_R16G16B16A16_USCALED };
                    return integer ? uint[size - 1] : normalize ? unorm[size - 1] : scaled[size - 1];
                }
                case 0x1402: // GL_SHORT
                {
                    const VkFormat snorm[] = { VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16_SNORM,
                        VK_FORMAT_R16G16B16A16_SNORM };
                    const VkFormat sint[] = { VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16_SINT,
                        VK_FORMAT_R16G16B16A16_SINT };
                    const VkFormat scaled[] = { VK_FORMAT_R16_SSCALED, VK_FORMAT_R16G16_SSCALED,
                        VK_FORMAT_R16G16B16_SSCALED, VK_FORMAT_R16G16B16A16_SSCALED };
                    return integer ? sint[size - 1] : normalize ? snorm[size - 1] : scaled[size - 1];
                }
                case 0x1405: // GL_UNSIGNED_INT
                {
                    const VkFormat uint[] = { VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT,
                        VK_FORMAT_R32G32B32A32_UINT };
                    return integer ? uint[size - 1] : VK_FORMAT_UNDEFINED;
                }
                case 0x1404: // GL_INT
                {
                    const VkFormat sint[] = { VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT,
                        VK_FORMAT_R32G32B32A32_SINT };
                    return integer ? sint[size - 1] : VK_FORMAT_UNDEFINED;
                }
                default:
                    return VK_FORMAT_UNDEFINED;
            }
        }

        // Where a pass draws: the window or images
        struct Target
        {
            bool mWindow = true;
            Image* mColor = nullptr;
            Image* mColor1 = nullptr; // COLOR_BUFFER1 (the scene's normals)
            Image* mDepth = nullptr;
            uint32_t mWidth = 0, mHeight = 0;
            VkFormat mColorFormat = VK_FORMAT_UNDEFINED;
            VkFormat mDepthFormat = VK_FORMAT_UNDEFINED;
        };

        const char* sBlitVertex = R"(#version 320 es
out highp vec2 uv;
void main()
{
    vec2 position = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)) * 2.0 - 1.0;
    uv = position * 0.5 + 0.5;
    gl_Position = vec4(position, 0.0, 1.0);
}
)";
        const char* sBlitFragment = R"(#version 320 es
precision highp float;
in highp vec2 uv;
uniform sampler2D scene;
layout(location = 0) out vec4 color;
void main()
{
    color = vec4(texture(scene, uv).rgb, 1.0);
}
)";

        // osgViewer::StatsHandler's page (what F3 steps through: 0 none, then FRAME_RATE, VIEWER_STATS,
        // CAMERA_SCENE_STATS, VIEWER_SCENE_STATS), which it keeps to itself
        struct StatsPage : osgViewer::StatsHandler
        {
            static int of(const osgViewer::StatsHandler& handler) { return handler.*(&StatsPage::_statsType); }
        };

        // osgText::Text's arrays, which its draw binds itself (kept to itself)
        struct TextArrays : osgText::Text
        {
            static const Coords& coords(const osgText::Text& text) { return text.*(&TextArrays::_coords); }
            static const ColorCoords& colors(const osgText::Text& text) { return text.*(&TextArrays::_colorCoords); }
            static const TexCoords& texCoords(const osgText::Text& text) { return text.*(&TextArrays::_texcoords); }
        };

        // Frame generation (OPENMW_FRAMEGEN, the launcher's switch): the last rendered frame seen from another camera,
        // as the OpenGL ES path's reprojection (framegen.cpp) does it. Each pixel's depth gives its place in view space,
        // the camera's motion where it is seen from the new view; the pixel is looked up again from there, so the
        // depth is the point's and not whatever was in front of it. The first person model (in the opaque depth, not
        // the scene's) moves with the camera, and the GUI (where the window differs from the scene) stays put.
        // amount: how far along the motion (1: the whole way to the view the matrix goes to).
        const char* sWarpFragment = R"(#version 320 es
precision highp float;
precision highp sampler2D;
in highp vec2 uv;
uniform sampler2D uCur;
uniform sampler2D uFinal;
uniform sampler2D uDepth;
uniform sampler2D uOpaqueDepth;
uniform mat4 uCurToPrev;
uniform vec4 uProj;
uniform vec4 uPlayerFeet; // third person: the player's feet in view space, w the radius around the line to its head
uniform vec4 uPlayerHead; // its head, w 1 when there is a player to keep
uniform float uReversed;
uniform float uAmount;
uniform float uTint;
uniform float uFirstPerson;
layout(location = 0) out vec4 color;
float viewZ(float d)
{
    float n = uProj.z;
    float f = uProj.w;
    if (uReversed > 0.5)
        return -n * f / (d * (f - n) + n);
    return 2.0 * f * n / ((d * 2.0 - 1.0) * (f - n) - (f + n));
}
vec3 viewPosition(vec2 at, float z)
{
    vec2 ndc = at * 2.0 - 1.0;
    return vec3(ndc.x * -z / uProj.x, ndc.y * -z / uProj.y, z);
}
// The model that goes with the camera (its pixels stay where they are). First person: the scene's depth was cleared
// for the model, so it holds only that model. Third person: what stands where the player does.
bool modelAt(vec2 at)
{
    float d = texture(uDepth, at).r;
    if (uFirstPerson > 0.5)
        return uReversed > 0.5 ? d > 0.0 : d < 1.0;
    if (uPlayerHead.w < 0.5)
        return false;
    vec3 fromFeet = viewPosition(at, viewZ(d)) - uPlayerFeet.xyz;
    vec3 up = uPlayerHead.xyz - uPlayerFeet.xyz;
    float along = dot(fromFeet, up) / dot(up, up);
    vec3 aside = fromFeet - up * along;
    return along > 0.0 && along < 1.0 && dot(aside, aside) < uPlayerFeet.w * uPlayerFeet.w;
}
// the model at or next to a pixel (its blended edges are drawn a little past its depth)
bool nearModel(vec2 at)
{
    vec2 texel = 1.0 / vec2(textureSize(uDepth, 0));
    if (uFirstPerson < 0.5)
        return modelAt(at) || modelAt(at + texel * vec2(-2.0, -2.0)) || modelAt(at + texel * vec2(2.0, -2.0))
            || modelAt(at + texel * vec2(-2.0, 2.0)) || modelAt(at + texel * vec2(2.0, 2.0));
    vec4 a = textureGather(uDepth, at);
    vec4 b = textureGather(uDepth, at + texel * vec2(-2.0, -2.0));
    vec4 c = textureGather(uDepth, at + texel * vec2(2.0, -2.0));
    vec4 d = textureGather(uDepth, at + texel * vec2(-2.0, 2.0));
    vec4 e = textureGather(uDepth, at + texel * vec2(2.0, 2.0));
    if (uReversed > 0.5)
    {
        a = max(max(max(a, b), max(c, d)), e);
        return max(max(a.x, a.y), max(a.z, a.w)) > 0.0;
    }
    a = min(min(min(a, b), min(c, d)), e);
    return min(min(a.x, a.y), min(a.z, a.w)) < 1.0;
}
// how the world at a pixel moves on the screen
vec2 motionAt(vec2 at)
{
    float z;
    if (uFirstPerson > 0.5)
        // the world's depth is the opaque copy's, from before the clear
        z = viewZ(texture(uOpaqueDepth, at).r);
    else
    {
        z = viewZ(texture(uDepth, at).r);
        if (-viewZ(texture(uOpaqueDepth, at).r) < -z * 0.9)
            return vec2(0.0);
    }
    vec4 p = uCurToPrev * vec4(viewPosition(at, z), 1.0);
    if (p.z > -0.001)
        return vec2(0.0);
    vec2 prev = vec2(p.x * uProj.x, p.y * uProj.y) / -p.z;
    return (prev * 0.5 + 0.5) - at;
}
void main()
{
    vec3 final = texture(uFinal, uv).rgb;
    if (any(greaterThan(abs(final - texture(uCur, uv).rgb), vec3(0.02))))
    {
        color = vec4(final, 1.0);
        return;
    }
    vec2 motion = vec2(0.0);
    if (!modelAt(uv))
    {
        // the motion of what is seen here: looked up again where it comes from, unless the model is in front of it
        // there (the world behind the model was not drawn: it is filled in below, with the motion found here)
        motion = motionAt(uv);
        vec2 from = clamp(uv - uAmount * motion, vec2(0.0), vec2(1.0));
        if (!modelAt(from))
            motion = motionAt(from);
    }
    // what comes into view past the screen's edge is mirrored from what is inside it (clamping it would stretch the
    // edge's pixels into streaks across a band as wide as the turn)
    vec2 inCur = 1.0 - abs(1.0 - mod(uv - uAmount * motion, 2.0));
    // the world seen where the model was in front of it (the model's pixels are where they were, motion 0): the world
    // behind the model was never drawn, so the world at the model's edge stands in (the model's own colour would be a
    // copy of the model moved with the world). The edge along the motion first: what is past it is what is drawn
    // beside these pixels, and the other end of them is against the model itself.
    if ((uFirstPerson > 0.5 || uPlayerHead.w > 0.5) && motion != vec2(0.0) && nearModel(inCur))
    {
        vec2 step = normalize(motion) / vec2(textureSize(uCur, 0));
        float edge = 0.0;
        for (int i = 1; i <= 32 && edge == 0.0; ++i)
            if (!nearModel(clamp(inCur + step * float(i) * 8.0, vec2(0.0), vec2(1.0))))
                edge = float(i) * 8.0;
        for (int i = 1; i <= 32 && edge == 0.0; ++i)
            if (!nearModel(clamp(inCur - step * float(i) * 8.0, vec2(0.0), vec2(1.0))))
                edge = float(i) * -8.0;
        if (edge != 0.0)
        {
            // the edge to a texel: between the last step in the model and the first past it
            float inside = abs(edge) - 8.0;
            float outside = abs(edge);
            for (int i = 0; i < 3; ++i)
            {
                float middle = (inside + outside) * 0.5;
                if (nearModel(clamp(inCur + step * sign(edge) * middle, vec2(0.0), vec2(1.0))))
                    inside = middle;
                else
                    outside = middle;
            }
            inCur = clamp(inCur + step * sign(edge) * outside, vec2(0.0), vec2(1.0));
        }
    }
    color = vec4(texture(uCur, inCur).rgb + vec3(0.25, 0.0, 0.0) * uTint, 1.0);
}
)";

        // The view part way (fraction) from one camera to another: positions in a line, rotations along the shortest arc
        osg::Matrixd viewBetween(const osg::Matrixd& from, const osg::Matrixd& to, double fraction)
        {
            const osg::Matrixd a = osg::Matrixd::inverse(from);
            const osg::Matrixd b = osg::Matrixd::inverse(to);
            osg::Quat rotation;
            rotation.slerp(fraction, a.getRotate(), b.getRotate());
            const osg::Vec3d position = a.getTrans() * (1.0 - fraction) + b.getTrans() * fraction;
            return osg::Matrixd::inverse(osg::Matrixd::rotate(rotation) * osg::Matrixd::translate(position));
        }

        // The entries of a map that are stale in 1/300 of its buckets (the next ones from [next]): all of them every 300
        // frames, a few hundred a frame
        template <class Map, class Stale>
        void sweepSlice(Map& map, size_t& next, const Stale& stale)
        {
            const size_t buckets = map.bucket_count();
            if (buckets == 0)
                return;
            if (next >= buckets)
                next = 0;
            const size_t end = std::min(buckets, next + buckets / 300 + 1);
            std::vector<typename Map::key_type> gone;
            for (size_t bucket = next; bucket < end; ++bucket)
                for (auto it = map.begin(bucket); it != map.end(bucket); ++it)
                    if (stale(it->second))
                        gone.push_back(it->first);
            next = end;
            for (const auto& key : gone)
                map.erase(key);
        }

        class Translator
        {
        public:
            explicit Translator(Context& context)
                : mContext(context)
                , mResources(context)
                , mPrograms(context)
            {
            }

            void frame(osgUtil::SceneView& sceneView)
            {
                const osg::Timer_t start = osg::Timer::instance()->tick();
                // as SceneView::draw: the state carries the frame's stamp (draw callbacks read it)
                if (osg::State* state = sceneView.getState())
                    state->setFrameStamp(sceneView.getFrameStamp());
                generateFrames(sceneView);
                mPrograms.poll();
                mPrograms.checkSources();
                if (!mContext.beginFrame())
                {
                    runFinalDrawCallbacks(sceneView, false);
                    return;
                }
                const osg::Timer_t begun = osg::Timer::instance()->tick();
                mWaitSeconds += osg::Timer::instance()->delta_s(start, begun);
                mResources.beginFrame();
                mState = sceneView.getState();
                mFrameNumber = sceneView.getFrameStamp() ? sceneView.getFrameStamp()->getFrameNumber() : 0;
                mCameraStats = sceneView.getCamera() ? sceneView.getCamera()->getStats() : nullptr;
                ++mFrameCounter;
                // the leaves' states and shared blocks are found by frame stamp; the ones not used for a while go. A
                // slice of them a frame: there are tens of thousands, and walking them all at once took 75 ms on the
                // SM-T720
                const auto stale = [this](const auto& entry) { return mFrameCounter - entry.mFrame > 60; };
                sweepSlice(mAccumulated, mAccumulatedSweep, stale);
                sweepSlice(mSharedBlocks, mSharedSweep, stale);
                mKeep.clear();
                forgetBindings();
                // the geometries let go of or not drawn for a while
                if (mFrameCounter % 300 == 0)
                    for (auto it = mGeometries.begin(); it != mGeometries.end();)
                        if (!it->second.mGeometry || it->second.mGeometry->referenceCount() <= 1
                            || mFrameCounter - it->second.mLastUse > 600)
                            it = mGeometries.erase(it);
                        else
                            ++it;
                // the states not reached for a while (their state sets changed or gone)
                if (mFrameCounter % 60 == 0)
                    for (auto it = mSteps.begin(); it != mSteps.end();)
                        if (mFrameCounter - it->second.mLastUse > 120)
                            it = mSteps.erase(it);
                        else
                            ++it;
                mSceneColor = nullptr;
                mPostColor = nullptr;
                mFirstPersonCleared = false;
                mSceneDepth = nullptr;
                mSceneOpaqueDepth = nullptr;
                mCamerasDrawn[mFrameCounter % 2].clear();
                if (mFrameNumber % 300 == 0)
                    for (auto it = mParticles.begin(); it != mParticles.end();)
                    {
                        osg::ref_ptr<const osgParticle::ParticleSystem> system;
                        if (!it->second.mSystem.lock(system) || mFrameNumber - it->second.mLastUse > 600)
                            it = mParticles.erase(it);
                        else
                            ++it;
                    }
                mDraws = mSkipped = 0;
                copyFramebufferCallbacks(sceneView);
                renderCompositeMaps();
                renderRipples();
                if (osgUtil::RenderStage* stage = sceneView.getRenderStage())
                    drawStage(*stage);
                drawStatistics(sceneView);
                mContext.endWindowPass();
                keepLoadingWindow(sceneView);
                readImages();
                runFinalDrawCallbacks(sceneView, true);
                captureForFrameGen();
                // the statistics' GPU line (F3), as osgViewer's Renderer gives it from OpenGL's timer queries: the
                // GPU's time of the latest frame done, on the main camera's statistics
                const double gpuMs = mContext.gpuMilliseconds();
                mGpuMsSum += gpuMs;
                if (osg::Stats* stats = mCameraStats.get(); stats != nullptr && stats->collectStats("gpu"))
                {
                    double begin = 0.0;
                    stats->getAttribute(mFrameNumber, "Draw traversal begin time", begin);
                    stats->setAttribute(mFrameNumber, "GPU draw begin time", begin);
                    stats->setAttribute(mFrameNumber, "GPU draw end time", begin + gpuMs / 1000.0);
                    stats->setAttribute(mFrameNumber, "GPU draw time taken", gpuMs / 1000.0);
                }
                if (mSceneColor != nullptr)
                {
                    mLastSceneColor = mSceneColor;
                    mLastSceneFrame = mFrameCounter;
                }
                mRecordSeconds += osg::Timer::instance()->delta_s(begun, osg::Timer::instance()->tick());
                if (mFrameCounter % 300 == 30)
                {
                    const osg::Timer_t now = osg::Timer::instance()->tick();
                    const double seconds = mLastReport ? osg::Timer::instance()->delta_s(mLastReport, now) : 0.0;
                    Log(Debug::Info) << "Metal: frame " << mFrameCounter << ": " << mDraws << " draws, " << mSkipped
                                     << " left out, " << (seconds > 0 ? 300.0 / seconds : 0.0) << " fps; per frame "
                                     << mRecordSeconds / 300 * 1000 << " ms recording, " << mWaitSeconds / 300 * 1000
                                     << " ms waiting for the GPU, " << mGpuMsSum / 300 << " ms on the GPU; states "
                                     << mSteps.size() << ", " << mStepsNew / 300 << " new and " << mStepsChanged / 300
                                     << " made volatile a frame; " << mResources.mStreamedBytes / 300 / 1024
                                     << " KiB streamed a frame; " << mClippedDraws / 300 << " clipped draws a frame; "
                                     << mOpaqueCopies << " opaque copies; binds a frame: " << mPipelineBinds / 300
                                     << " pipelines (" << mPipelineCount << " made), " << mVertexBinds / 300
                                     << " vertex, " << mSetBinds / 300 << " texture sets (" << mSameImageBinds / 300
                                     << " with the last one's textures), " << mUniformBytes / 300 / 1024
                                     << " KiB of uniforms bound; " << mVertices / 300 / 3
                                     << " triangles (vertices / 3) a frame; " << mGenerated << " generated frames; "
                                     << mDispatches << " compute dispatches";
                    mDispatches = 0;
                    mGenerated = 0;
                    mVertices = 0;
                    mGpuMsSum = 0.0;
                    mPipelineBinds = mVertexBinds = mSetBinds = mUniformBytes = mSameImageBinds = 0;
                    mClippedDraws = 0;
                    mOpaqueCopies = 0;
                    mStepsNew = mStepsChanged = 0;
                    mResources.mStreamedBytes = 0;
                    mLastReport = now;
                    mRecordSeconds = mWaitSeconds = 0.0;
                }
            }

        private:
            // OpaqueColorBinCallback::resolve: the scene stage's colour and depth copied into the opaque copy's
            // textures. The stage's render pass is ended for it and begun again, loading what it drew.
            void copyOpaque(const osgUtil::RenderBin& bin, const OpaqueColorBinCallbackMirror& callback)
            {
                const osgUtil::RenderStage* stage = bin.getStage();
                if (stage == nullptr || mEncoder == nil || mTarget.mWindow || mTarget.mColor == nullptr)
                    return;
                const unsigned frameId = mFrameNumber % 2;
                const osg::FrameBufferObject* fbo = stage->getFrameBufferObject();
                const osg::FrameBufferObject* opaqueFbo = callback.mOpaqueFbo[frameId].get();
                if (fbo == nullptr || opaqueFbo == nullptr
                    || (fbo != callback.mFbo[frameId].get() && fbo != callback.mMsaaFbo[frameId].get()))
                    return;
                {
                    osg::RenderInfo info(mState, nullptr);
                    info.pushCamera(const_cast<osg::Camera*>(stage->getCamera()));
                    // frame generation reads the world's depth from it when the first person model clears the depth
                    if (!Alpha3::opaqueCopyWanted(bin, info) && !(frameGenWanted() && mGenLock.generated() > 0))
                        return;
                }
                const osg::Texture* colorTexture
                    = opaqueFbo->getAttachment(osg::Camera::COLOR_BUFFER0).getTexture();
                const osg::Texture* depthTexture
                    = opaqueFbo->getAttachment(osg::Camera::PACKED_DEPTH_STENCIL_BUFFER).getTexture();
                if (depthTexture == nullptr)
                    depthTexture = opaqueFbo->getAttachment(osg::Camera::DEPTH_BUFFER).getTexture();
                Image* color = colorTexture ? mResources.renderTarget(*colorTexture) : nullptr;
                Image* depth = depthTexture ? mResources.renderTarget(*depthTexture) : nullptr;
                if (color == nullptr && depth == nullptr)
                    return;

                mContext.endEncoder();
                mEncoder = nil;
                ++mOpaqueCopies;
                if (color != nullptr)
                    blit(*mTarget.mColor, *color);
                if (depth != nullptr && mTarget.mDepth != nullptr)
                {
                    if (depth->mFormat == mTarget.mDepth->mFormat && depth->mWidth == mTarget.mDepth->mWidth
                        && depth->mHeight == mTarget.mDepth->mHeight)
                    {
                        copyDepth(*mTarget.mDepth, *depth);
                        mSceneOpaqueDepth = depth;
                    }
                    else if (!mOpaqueDepthWarned)
                    {
                        mOpaqueDepthWarned = true;
                        Log(Debug::Warning) << "Metal: the opaque depth copy has another format or size than the "
                                               "scene's depth; not copied";
                    }
                }

                // the pass again, loading what was drawn
                const Target target = mTarget;
                beginTargetPass(target, mArea, nullptr, false, 1.f, 0, @"scene (after the opaque copy)");
            }

            void copyDepth(Image& source, Image& target)
            {
                copyTexture(source, target);
            }

            // A texture into another of its format and size, as it is
            void copyTexture(Image& source, Image& target)
            {
                mEncoder = nil;
                id<MTLBlitCommandEncoder> encoder = mContext.blitEncoder();
                [encoder copyFromTexture:source.mTexture
                             sourceSlice:0
                             sourceLevel:0
                            sourceOrigin:MTLOriginMake(0, 0, 0)
                              sourceSize:MTLSizeMake(source.mWidth, source.mHeight, 1)
                               toTexture:target.mTexture
                        destinationSlice:0
                        destinationLevel:0
                       destinationOrigin:MTLOriginMake(0, 0, 0)];
            }

            // The main camera's draw callbacks that copy the window into a texture (the loading screen's background):
            // the last scene drawn is copied instead (it has no GUI on it, which the loading screen draws anyway). Only
            // on the first frame after the loading screen set the callback up (its one shot flag): the scene drawn
            // while loading is empty, and the background is meant to be the game as it was.
            void copyFramebufferCallbacks(osgUtil::SceneView& sceneView)
            {
                const osg::Camera* camera = sceneView.getCamera();
                if (camera == nullptr)
                    return;
                for (const osg::Callback* callback = camera->getInitialDrawCallback(); callback != nullptr;
                     callback = callback->getNestedCallback())
                {
                    if (std::strstr(typeid(*callback).name(), "CopyFramebufferToTextureCallback") == nullptr)
                        continue;
                    const auto& copy = static_cast<const CopyFramebufferCallback&>(
                        *static_cast<const osg::Camera::DrawCallback*>(callback));
                    if (!copy.mTexture || !copy.mOneshot)
                        continue;
                    copy.mOneshot = false;
                    // the window of the frame before when the loading screen drew it (its picture is the one to keep:
                    // on a fresh start the navigation mesh is built over the loading screen's wallpaper), otherwise
                    // the scene, which the image is let go of when no camera draws into it for 600 frames
                    Image* scene = mLastSceneColor;
                    if (scene != nullptr && (scene->mDepthAspect || mFrameCounter - mLastSceneFrame > 300))
                        scene = nullptr;
                    if (mLoadingWindow != nullptr && mLoadingWindowFrame + 1 == mFrameCounter)
                        scene = mLoadingWindow;
                    const VkExtent2D window = windowExtent();
                    Image* target = mResources.renderTarget(*copy.mTexture, scene ? scene->mWidth : window.width,
                        scene ? scene->mHeight : window.height, 0x8058);
                    if (target == nullptr)
                        continue;
                    if (scene != nullptr)
                        blit(*scene, *target);
                    else // no game drawn yet (a saved game loaded from the main menu): black
                        clearColor(*target, { { 0.f, 0.f, 0.f, 1.f } });
                }
            }

            // A frame the loading screen draws (it marks them in the viewer's stats): its window is kept, for the
            // loading screen's copy of it on the next frame
            void keepLoadingWindow(osgUtil::SceneView& sceneView)
            {
                const osg::Camera* camera = sceneView.getCamera();
                auto* viewer = camera ? dynamic_cast<osgViewer::ViewerBase*>(const_cast<osg::View*>(camera->getView()))
                                      : nullptr;
                osg::Stats* stats = viewer ? viewer->getViewerStats() : nullptr;
                double loading = 0.0;
                if (stats == nullptr || !stats->getAttribute(mFrameNumber, "Loading", loading) || loading == 0.0)
                    return;
                const VkExtent2D window = windowExtent();
                Image* image = mResources.renderTarget(&mLoadingWindowKey, window.width, window.height, 0x8058);
                if (image == nullptr || !mContext.blitWindow(image->mTexture))
                    return;
                mLoadingWindow = image;
                mLoadingWindowFrame = mFrameCounter;
            }

            void runFinalDrawCallbacks(osgUtil::SceneView& sceneView, bool afterFrame)
            {
                osg::ref_ptr<osg::Camera> camera = sceneView.getCamera();
                if (!camera || camera->getFinalDrawCallback() == nullptr)
                    return;
                osg::ref_ptr<osg::Camera::DrawCallback> callback = camera->getFinalDrawCallback();
                osg::ref_ptr<osg::State> state = sceneView.getState();
                osg::View* view = camera->getView();
                auto run = [camera, callback, state, view] {
                    osg::RenderInfo info(state.get(), view);
                    info.pushCamera(camera.get());
                    callback->run(info);
                };
                if (afterFrame)
                    mContext.afterFrame(run);
                else
                    run();
            }

            // The window read into images, as glReadPixels would (rows bottom first, RGB): the scene's image is read,
            // which is the window under the GUI (post-processing is not done yet)
            void readImages()
            {
                if (mReads.empty())
                    return;
                std::vector<PendingRead> reads;
                reads.swap(mReads);
                Image* scene = mSceneColor != nullptr ? mSceneColor : mLastSceneColor;
                if (scene == nullptr || scene->mDepthAspect)
                {
                    Log(Debug::Warning) << "Metal: no scene image to read back for a screenshot";
                    return;
                }
                Image* copy = mResources.renderTarget(&mReadKey, scene->mWidth, scene->mHeight, 0x8058);
                if (copy == nullptr)
                    return;
                blit(*scene, *copy);
                const uint32_t width = copy->mWidth, height = copy->mHeight;
                Buffer buffer = mResources.readbackBuffer(uint64_t(width) * height * 4);
                if (buffer.mBuffer == nil || buffer.mMapped == nullptr)
                    return;
                mEncoder = nil;
                id<MTLBlitCommandEncoder> encoder = mContext.blitEncoder();
                [encoder copyFromTexture:copy->mTexture
                                 sourceSlice:0
                                 sourceLevel:0
                                sourceOrigin:MTLOriginMake(0, 0, 0)
                                  sourceSize:MTLSizeMake(width, height, 1)
                                    toBuffer:buffer.mBuffer
                           destinationOffset:0
                      destinationBytesPerRow:width * 4
                    destinationBytesPerImage:uint64_t(width) * height * 4];
                // (the frame is waited for before the work after it: the buffer holds the rows by then)
                mContext.afterFrame([buffer, width, height, reads] {
                    const uint8_t* pixels = buffer.mMapped;
                    for (const PendingRead& read : reads)
                    {
                        // ReadImageFromFramebufferCallback: the middle of the window at the image's aspect, scaled
                        const double aspect = double(read.mWidth) / double(std::max(read.mHeight, 1));
                        const int screenW = int(width), screenH = int(height);
                        const int left = std::max(0, static_cast<int>(screenW - screenH * aspect) / 2);
                        const int top = std::max(0, static_cast<int>(screenH - screenW / aspect) / 2);
                        const int w = screenW - left * 2, h = screenH - top * 2;
                        if (w <= 0 || h <= 0)
                            continue;
                        osg::Image& image = *read.mImage;
                        image.allocateImage(w, h, 1, GL_RGB, GL_UNSIGNED_BYTE);
                        for (int y = 0; y < h; ++y)
                        {
                            const uint8_t* from = pixels + (size_t(top + y) * width + left) * 4;
                            uint8_t* to = image.data(0, y);
                            for (int x = 0; x < w; ++x, from += 4, to += 3)
                                to[0] = from[0], to[1] = from[1], to[2] = from[2];
                        }
                        image.scaleImage(read.mWidth, read.mHeight, 1);
                    }
                });
            }

            // An image into another (scaled, its format converted when they differ)
            void blit(Image& source, Image& target)
            {
                if (source.mTexture == nil || target.mTexture == nil)
                    return;
                if (source.mTexture.pixelFormat == target.mTexture.pixelFormat && source.mWidth == target.mWidth
                    && source.mHeight == target.mHeight)
                {
                    copyTexture(source, target);
                    return;
                }
                mEncoder = nil;
                mContext.drawCopy(source.mTexture, target.mTexture, false, false);
            }

            void drawStage(osgUtil::RenderStage& stage)
            {
                for (auto& pre : stage.getPreRenderList())
                    drawStage(*pre.second);
                drawStageItself(stage);
                for (auto& post : stage.getPostRenderList())
                    drawStage(*post.second);
            }

            static bool hasLeaves(const osgUtil::RenderBin& bin)
            {
                if (!bin.getRenderLeafList().empty())
                    return true;
                for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                    if (!graph->_leaves.empty())
                        return true;
                for (const auto& [number, child] : bin.getRenderBinList())
                    if (hasLeaves(*child))
                        return true;
                return false;
            }

            // The images a stage draws into: its FBO (set by the engine during the cull) or its camera's attachments
            bool resolveTarget(osgUtil::RenderStage& stage, Target& target)
            {
                osg::Camera* camera = stage.getCamera();
                const osg::FrameBufferObject* fbo = stage.getFrameBufferObject();
                auto use = [&](osg::Camera::BufferComponent component, Image* image) {
                    if (image == nullptr)
                        return;
                    if (component == osg::Camera::DEPTH_BUFFER || component == osg::Camera::PACKED_DEPTH_STENCIL_BUFFER
                        || component == osg::Camera::STENCIL_BUFFER)
                    {
                        if (image->mDepthAspect && target.mDepth == nullptr)
                            target.mDepth = image;
                    }
                    else if ((component == osg::Camera::COLOR_BUFFER || component == osg::Camera::COLOR_BUFFER0)
                        && !image->mDepthAspect && target.mColor == nullptr)
                        target.mColor = image;
                    else if (component == osg::Camera::COLOR_BUFFER1 && !image->mDepthAspect
                        && target.mColor1 == nullptr)
                        target.mColor1 = image;
                };
                if (fbo != nullptr)
                {
                    target.mWindow = false;
                    for (const auto& [component, attachment] : fbo->getAttachmentMap())
                    {
                        if (const osg::Texture* texture = attachment.getTexture())
                            use(component, mResources.renderTarget(*texture));
                        else if (const osg::RenderBuffer* buffer = attachment.getRenderBuffer())
                            use(component, mResources.renderTarget(*buffer));
                    }
                }
                else if (camera != nullptr
                    && camera->getRenderTargetImplementation() == osg::Camera::FRAME_BUFFER_OBJECT
                    && !camera->getBufferAttachmentMap().empty())
                {
                    target.mWindow = false;
                    const osg::Viewport* viewport = stage.getViewport();
                    for (const auto& [component, attachment] : camera->getBufferAttachmentMap())
                    {
                        if (attachment._texture.valid())
                            use(component, mResources.renderTarget(*attachment._texture));
                        else if (!attachment._image.valid() && viewport != nullptr)
                        {
                            const void* key = reinterpret_cast<const char*>(camera) + component;
                            use(component,
                                mResources.renderTarget(key, static_cast<uint32_t>(viewport->width()),
                                    static_cast<uint32_t>(viewport->height()), attachment._internalFormat));
                        }
                    }
                }
                if (target.mWindow)
                {
                    const VkExtent2D extent = windowExtent();
                    target.mWidth = extent.width;
                    target.mHeight = extent.height;
                    target.mColorFormat = VK_FORMAT_B8G8R8A8_UNORM; // the layer's (Context::windowFormat)
                    target.mDepthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT; // the window's depth (Device::mDepthFormat)
                    return true;
                }
                if (target.mColor == nullptr && target.mDepth == nullptr)
                    return false;
                const Image* any = target.mColor ? target.mColor : target.mDepth;
                target.mWidth = any->mWidth;
                target.mHeight = any->mHeight;
                if (target.mColor && target.mDepth
                    && (target.mDepth->mWidth != target.mWidth || target.mDepth->mHeight != target.mHeight))
                    target.mDepth = nullptr;
                if (target.mColor1 != nullptr
                    && (target.mColor == nullptr || target.mColor1->mWidth != target.mWidth
                        || target.mColor1->mHeight != target.mHeight))
                    target.mColor1 = nullptr;
                target.mColorFormat = target.mColor ? target.mColor->mFormat : VK_FORMAT_UNDEFINED;
                target.mDepthFormat = target.mDepth ? target.mDepth->mFormat : VK_FORMAT_UNDEFINED;
                return true;
            }

            void drawStageItself(osgUtil::RenderStage& stage)
            {
                struct KindOf
                {
                    StageKind& mKind;
                    StageKind mBefore;
                    ~KindOf() { mKind = mBefore; }
                } kindOf{ mStageKind, mStageKind };
                mStageKind = StageKind::Other;
                if (const osg::Camera* camera = stage.getCamera())
                    mStageKind = camera->getName() == "SceneCam" ? StageKind::Scene
                        : camera->getName() == "ReflectionCamera" ? StageKind::Reflection
                                                                  : StageKind::Other;
                const unsigned clearMask = stage.getClearMask();
                const bool clearColor = (clearMask & 0x4000) != 0;
                const bool clearDepth = (clearMask & 0x100) != 0;
                const bool clearStencil = (clearMask & 0x400) != 0;
                if (!hasLeaves(stage) && !clearColor && !clearDepth)
                    return;
                // the ripples' camera: its surface is simulated at the start of the next frame (renderRipples)
                if (const osg::Camera* camera = stage.getCamera();
                    camera != nullptr && std::strstr(typeid(*camera).name(), "MWRender7Ripples") != nullptr)
                {
                    mRipples = static_cast<const RipplesCameraMirror*>(camera)->mRipples.get();
                    return;
                }
                Target target;
                if (!resolveTarget(stage, target))
                    return;
                if (!target.mWindow && stage.getFrameBufferObject() != nullptr && target.mColor != nullptr)
                    mSceneColor = target.mColor;
                // A camera without a name drawn into a texture that was not drawn the frame before (the local map's,
                // drawn once for a cell): its programs and pipelines are made now rather than its draws left out
                // while they are made, which would leave its texture without them for good. The cameras drawn every
                // frame (scene, shadows, reflection) have names.
                const osg::Camera* stageCamera = stage.getCamera();
                const bool oneShot = !target.mWindow && stageCamera != nullptr && stageCamera->getName().empty()
                    && mCamerasDrawn[(mFrameCounter + 1) % 2].count(stageCamera) == 0;
                if (stageCamera != nullptr)
                    mCamerasDrawn[mFrameCounter % 2].insert(stageCamera);
                const bool waitedBefore = mWaitForPrograms;
                mWaitForPrograms = waitedBefore || oneShot;
                struct RestoreWait
                {
                    bool& mWait;
                    bool mBefore;
                    ~RestoreWait() { mWait = mBefore; }
                } restoreWait{ mWaitForPrograms, waitedBefore };

                // the stage's viewport, which bounds its clears too (OSG sets the scissor to it)
                const osg::Viewport* viewport = stage.getViewport();
                VkRect2D area{ { 0, 0 }, { target.mWidth, target.mHeight } };
                if (viewport != nullptr)
                {
                    area.offset = { static_cast<int32_t>(viewport->x()), static_cast<int32_t>(viewport->y()) };
                    area.extent = { static_cast<uint32_t>(viewport->width()), static_cast<uint32_t>(viewport->height()) };
                }
                // held to the target
                {
                    const int32_t x0 = std::clamp(area.offset.x, 0, static_cast<int32_t>(target.mWidth));
                    const int32_t y0 = std::clamp(area.offset.y, 0, static_cast<int32_t>(target.mHeight));
                    const int32_t x1 = std::clamp(area.offset.x + static_cast<int32_t>(area.extent.width), x0,
                        static_cast<int32_t>(target.mWidth));
                    const int32_t y1 = std::clamp(area.offset.y + static_cast<int32_t>(area.extent.height), y0,
                        static_cast<int32_t>(target.mHeight));
                    area = { { x0, y0 }, { static_cast<uint32_t>(x1 - x0), static_cast<uint32_t>(y1 - y0) } };
                }
                if (area.extent.width == 0 || area.extent.height == 0)
                    return;
                const bool whole = area.offset.x <= 0 && area.offset.y <= 0
                    && area.offset.x + static_cast<int32_t>(area.extent.width) >= static_cast<int32_t>(target.mWidth)
                    && area.offset.y + static_cast<int32_t>(area.extent.height) >= static_cast<int32_t>(target.mHeight);

                const osg::Vec4& color = stage.getClearColor();
                const float clearValues[4] = { color.r(), color.g(), color.b(), color.a() };
                const float depthValue = static_cast<float>(stage.getClearDepth());
                const uint32_t stencilValue = static_cast<uint32_t>(stage.getClearStencil());
                mContext.endWindowPass();
                // compute shaders (clustered lighting's), before the draws that read what they write (recorded on the
                // commands submitted ahead of the frame's)
                runCompute(stage);

                // the clear is the pass's when it covers the target; a part of it is cleared with a draw (clearRect)
                if (target.mWindow)
                {
                    if (!mContext.beginWindowPass(clearColor && whole, clearValues, (clearDepth || clearStencil) && whole,
                            depthValue, stencilValue))
                        return;
                    mEncoder = mContext.renderEncoder();
                    forgetBindings();
                    mTarget = target;
                    mArea = area;
                    mFlip = false;
                    mViewportDepth[0] = -1.f;
                    setViewport(0.f, 1.f);
                    setScissor(area);
                }
                else
                {
                    const osg::Camera* camera = stage.getCamera();
                    const std::string label = (camera && !camera->getName().empty() ? camera->getName() : std::string("RTT"))
                        + " " + std::to_string(area.extent.width) + "x" + std::to_string(area.extent.height);
                    if (!beginTargetPass(target, area, clearColor && whole ? clearValues : nullptr,
                            (clearDepth || clearStencil) && whole, depthValue, stencilValue,
                            [NSString stringWithUTF8String:label.c_str()]))
                        return;
                }
                if (!whole && (clearColor || clearDepth || clearStencil))
                    clearRect(area, clearColor ? clearValues : nullptr, clearDepth, clearStencil, depthValue, stencilValue);

                stageClipPlanes(stage);
                std::vector<const osgUtil::RenderBin*> bins;
                drawBin(stage, bins);

                if (target.mWindow)
                    mContext.endWindowPass();
                else
                    mContext.endEncoder();
                mEncoder = nil;
            }

            // A render pass into a render target's images (loaded, or cleared), which becomes the target
            bool beginTargetPass(const Target& target, const VkRect2D& area, const float* clearColor, bool clearDepth,
                float depthValue, uint32_t stencilValue, NSString* label)
            {
                if (target.mColor == nullptr && target.mDepth == nullptr)
                    return false;
                MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
                const auto color = [&](MTLRenderPassColorAttachmentDescriptor* attachment, const Image& image) {
                    attachment.texture = image.mTexture;
                    attachment.loadAction = clearColor != nullptr ? MTLLoadActionClear : MTLLoadActionLoad;
                    attachment.storeAction = MTLStoreActionStore;
                    if (clearColor != nullptr)
                        attachment.clearColor
                            = MTLClearColorMake(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
                };
                if (target.mColor != nullptr)
                    color(pass.colorAttachments[0], *target.mColor);
                if (target.mColor1 != nullptr)
                    color(pass.colorAttachments[1], *target.mColor1);
                if (target.mDepth != nullptr)
                {
                    pass.depthAttachment.texture = target.mDepth->mTexture;
                    pass.depthAttachment.loadAction = clearDepth ? MTLLoadActionClear : MTLLoadActionLoad;
                    pass.depthAttachment.storeAction = MTLStoreActionStore;
                    pass.depthAttachment.clearDepth = depthValue;
                    if (target.mDepth->mStencilAspect)
                    {
                        pass.stencilAttachment.texture = target.mDepth->mTexture;
                        pass.stencilAttachment.loadAction = pass.depthAttachment.loadAction;
                        pass.stencilAttachment.storeAction = MTLStoreActionStore;
                        pass.stencilAttachment.clearStencil = stencilValue;
                    }
                }
                mEncoder = mContext.beginPass(pass, label);
                if (mEncoder == nil)
                    return false;
                forgetBindings();
                mTarget = target;
                mArea = area;
                mFlip = true; // drawn the OpenGL way up: the vertex stage turns y over
                mViewportDepth[0] = -1.f;
                setViewport(0.f, 1.f);
                setScissor(area);
                return true;
            }

            // A clear of a part of the target (Metal's passes clear whole images): a triangle over the target, held to
            // the area by the scissor, writing the colour, depth and stencil asked for
            void clearRect(const VkRect2D& area, const float* color, bool depth, bool stencil, float depthValue,
                uint32_t stencilValue)
            {
                if (mEncoder == nil || area.extent.width == 0 || area.extent.height == 0)
                    return;
                const bool hasColor = mTarget.mWindow || mTarget.mColor != nullptr;
                const bool hasDepth = mTarget.mWindow || mTarget.mDepth != nullptr;
                const bool withStencil = hasDepth && hasStencil(mTarget.mDepthFormat);
                const bool writeColor = color != nullptr && hasColor;
                const bool writeDepth = depth && hasDepth;
                const bool writeStencil = stencil && withStencil;
                if (!writeColor && !writeDepth && !writeStencil)
                    return;
                const MTLPixelFormat colorFormat = hasColor ? mtlFormat(mTarget.mColorFormat) : MTLPixelFormatInvalid;
                const MTLPixelFormat color1Format
                    = mTarget.mColor1 != nullptr ? mtlFormat(mTarget.mColor1->mFormat) : MTLPixelFormatInvalid;
                const MTLPixelFormat depthFormat = hasDepth ? mtlFormat(mTarget.mDepthFormat) : MTLPixelFormatInvalid;
                id<MTLRenderPipelineState> pipeline
                    = clearPipeline(colorFormat, color1Format, depthFormat, withStencil, writeColor);
                if (pipeline == nil)
                    return;
                __strong id<MTLDepthStencilState>& depthState = mClearDepthStates[(writeDepth ? 1 : 0) | (writeStencil ? 2 : 0)];
                if (depthState == nil)
                {
                    MTLDepthStencilDescriptor* descriptor = [MTLDepthStencilDescriptor new];
                    descriptor.depthCompareFunction = MTLCompareFunctionAlways;
                    descriptor.depthWriteEnabled = writeDepth;
                    if (writeStencil)
                    {
                        MTLStencilDescriptor* replace = [MTLStencilDescriptor new];
                        replace.stencilCompareFunction = MTLCompareFunctionAlways;
                        replace.stencilFailureOperation = MTLStencilOperationReplace;
                        replace.depthFailureOperation = MTLStencilOperationReplace;
                        replace.depthStencilPassOperation = MTLStencilOperationReplace;
                        descriptor.frontFaceStencil = replace;
                        descriptor.backFaceStencil = replace;
                    }
                    depthState = [mContext.mtl() newDepthStencilStateWithDescriptor:descriptor];
                }
                struct
                {
                    float mColor[4];
                    float mDepth;
                    float mPad[3];
                } values{};
                if (color != nullptr)
                    std::copy(color, color + 4, values.mColor);
                values.mDepth = std::clamp(depthValue, 0.f, 1.f);
                [mEncoder setRenderPipelineState:pipeline];
                [mEncoder setDepthStencilState:depthState];
                [mEncoder setStencilReferenceValue:stencilValue];
                [mEncoder setCullMode:MTLCullModeNone];
                [mEncoder setDepthBias:0.f slopeScale:0.f clamp:0.f];
                [mEncoder setVertexBytes:&values length:sizeof(values) atIndex:0];
                [mEncoder setFragmentBytes:&values length:sizeof(values) atIndex:0];
                const MTLViewport viewport{ 0.0, 0.0, double(mTarget.mWidth), double(mTarget.mHeight), 0.0, 1.0 };
                [mEncoder setViewport:viewport];
                setScissor(area);
                [mEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                ++mDraws;
                // what the draws after it set again
                forgetBindings();
                mViewportDepth[0] = -1.f;
                setViewport(0.f, 1.f);
                setScissor(mArea);
            }

            id<MTLRenderPipelineState> clearPipeline(
                MTLPixelFormat color, MTLPixelFormat color1, MTLPixelFormat depth, bool stencil, bool writeColor)
            {
                const std::array<uint64_t, 5> key{ uint64_t(color), uint64_t(color1), uint64_t(depth), stencil ? 1u : 0u,
                    writeColor ? 1u : 0u };
                auto found = mClearPipelines.find(key);
                if (found != mClearPipelines.end())
                    return found->second;
                __strong id<MTLRenderPipelineState>& pipeline = mClearPipelines[key];
                if (mClearLibrary == nil)
                {
                    static NSString* const source = @R"(#include <metal_stdlib>
using namespace metal;
struct Values { float4 color; float depth; };
struct Out { float4 position [[position]]; };
vertex Out clearVertex(uint id [[vertex_id]], constant Values& values [[buffer(0)]])
{
    float2 p = float2(float((id << 1) & 2), float(id & 2));
    Out out;
    out.position = float4(p * 2.0 - 1.0, values.depth, 1.0);
    return out;
}
fragment float4 clearFragment1(Out in [[stage_in]], constant Values& values [[buffer(0)]])
{
    return values.color;
}
struct Two { float4 c0 [[color(0)]]; float4 c1 [[color(1)]]; };
fragment Two clearFragment2(Out in [[stage_in]], constant Values& values [[buffer(0)]])
{
    Two out;
    out.c0 = values.color;
    out.c1 = values.color;
    return out;
}
)";
                    NSError* error = nil;
                    mClearLibrary = [mContext.mtl() newLibraryWithSource:source options:nil error:&error];
                    if (mClearLibrary == nil)
                    {
                        Log(Debug::Error) << "Metal: the clear shaders: " << [[error localizedDescription] UTF8String];
                        return nil;
                    }
                }
                MTLRenderPipelineDescriptor* descriptor = [MTLRenderPipelineDescriptor new];
                descriptor.label = @"clear";
                descriptor.vertexFunction = [mClearLibrary newFunctionWithName:@"clearVertex"];
                if (color != MTLPixelFormatInvalid)
                    descriptor.fragmentFunction = [mClearLibrary
                        newFunctionWithName:(color1 != MTLPixelFormatInvalid ? @"clearFragment2" : @"clearFragment1")];
                descriptor.colorAttachments[0].pixelFormat = color;
                descriptor.colorAttachments[0].writeMask = writeColor ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
                descriptor.colorAttachments[1].pixelFormat = color1;
                descriptor.colorAttachments[1].writeMask = writeColor ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
                descriptor.depthAttachmentPixelFormat = depth;
                if (stencil)
                    descriptor.stencilAttachmentPixelFormat = depth;
                NSError* error = nil;
                pipeline = [mContext.mtl() newRenderPipelineStateWithDescriptor:descriptor error:&error];
                if (pipeline == nil)
                    Log(Debug::Error) << "Metal: the clear pipeline: " << [[error localizedDescription] UTF8String];
                return pipeline;
            }
            id<MTLLibrary> mClearLibrary = nil;
            std::map<std::array<uint64_t, 5>, id<MTLRenderPipelineState>> mClearPipelines;
            id<MTLDepthStencilState> mClearDepthStates[4] = { nil, nil, nil, nil };

            VkExtent2D windowExtent() const { return { mContext.windowWidth(), mContext.windowHeight() }; }

            // The clip planes the stage's positional state (and what it inherits) puts in eye space, as
            // PositionalStateContainer::draw and ClipPlane::apply do; they stay set for the stages after, as OSG's
            void stageClipPlanes(osgUtil::RenderStage& stage)
            {
                const auto apply = [&](osgUtil::PositionalStateContainer* container, const osg::Matrix* postMult) {
                    if (container == nullptr)
                        return;
                    for (const auto& [attribute, matrix] : container->getAttrMatrixList())
                    {
                        const auto* clip = dynamic_cast<const osg::ClipPlane*>(attribute.get());
                        if (clip == nullptr || clip->getClipPlaneNum() >= 2)
                            continue;
                        osg::Matrix modelView = matrix.valid() ? osg::Matrix(*matrix) : osg::Matrix();
                        if (postMult != nullptr)
                            modelView = modelView * *postMult;
                        osg::Plane plane(clip->getClipPlane());
                        plane.transformProvidingInverse(osg::Matrix::inverse(modelView));
                        mClipPlanes[clip->getClipPlaneNum()] = plane.asVec4();
                        if (!mClipLogged)
                        {
                            mClipLogged = true;
                            const osg::Camera* camera = stage.getCamera();
                            Log(Debug::Info) << "Metal: clip plane " << clip->getClipPlaneNum() << " ("
                                             << (camera ? camera->getName() : std::string()) << ") eye space "
                                             << plane.asVec4().x() << " " << plane.asVec4().y() << " "
                                             << plane.asVec4().z() << " " << plane.asVec4().w();
                        }
                    }
                };
                apply(stage.getInheritedPositionalStateContainer(), &stage.getInheritedPositionalStateContainerMatrix());
                apply(stage.getPositionalStateContainer(), nullptr);
            }

            // A rectangle of the target as Metal's rows go: a render target's as they are (drawn the OpenGL way up),
            // the window's turned over
            VkRect2D scissorOf(const VkRect2D& area) const
            {
                VkRect2D rect = area;
                if (mTarget.mWindow)
                    rect.offset.y = static_cast<int32_t>(mTarget.mHeight) - area.offset.y
                        - static_cast<int32_t>(area.extent.height);
                rect.offset.x = std::max(rect.offset.x, 0);
                rect.offset.y = std::max(rect.offset.y, 0);
                rect.extent.width = std::min(rect.extent.width, mTarget.mWidth - static_cast<uint32_t>(rect.offset.x));
                rect.extent.height
                    = std::min(rect.extent.height, mTarget.mHeight - static_cast<uint32_t>(rect.offset.y));
                return rect;
            }

            void setScissor(const VkRect2D& area)
            {
                if (mEncoder == nil)
                    return;
                const VkRect2D rect = scissorOf(area);
                MTLScissorRect scissor{ static_cast<NSUInteger>(rect.offset.x), static_cast<NSUInteger>(rect.offset.y),
                    std::max<NSUInteger>(rect.extent.width, 1), std::max<NSUInteger>(rect.extent.height, 1) };
                // (a rectangle of nothing: one pixel at the corner, as Metal takes none smaller)
                [mEncoder setScissorRect:scissor];
            }

            void setViewport(float minDepth, float maxDepth)
            {
                if (mViewportDepth[0] == minDepth && mViewportDepth[1] == maxDepth)
                    return;
                mViewportDepth[0] = minDepth;
                mViewportDepth[1] = maxDepth;
                if (mEncoder == nil)
                    return;
                MTLViewport viewport;
                viewport.originX = mArea.offset.x;
                viewport.originY = mTarget.mWindow ? static_cast<double>(static_cast<int32_t>(mTarget.mHeight)
                                                         - mArea.offset.y - static_cast<int32_t>(mArea.extent.height))
                                                   : static_cast<double>(mArea.offset.y);
                viewport.width = mArea.extent.width;
                viewport.height = mArea.extent.height;
                viewport.znear = std::clamp(minDepth, 0.f, 1.f);
                viewport.zfar = std::clamp(maxDepth, 0.f, 1.f);
                [mEncoder setViewport:viewport];
            }

            void drawBin(const osgUtil::RenderBin& bin, std::vector<const osgUtil::RenderBin*>& bins)
            {
                bins.push_back(&bin);
                bool firstPerson = false;
                if (const osgUtil::RenderBin::DrawCallback* callback = bin.getDrawCallback())
                {
                    const std::string type = typeid(*callback).name();
                    // the distortion bin draws into a texture of its own (not done yet)
                    if (type.find("DistortionCallback") != std::string::npos)
                    {
                        bins.pop_back();
                        return;
                    }
                    // the copy of the opaque scene, before the bins after it are drawn
                    if (type.find("OpaqueColorBinCallback") != std::string::npos)
                        copyOpaque(bin, static_cast<const OpaqueColorBinCallbackMirror&>(*callback));
                    // the first person view: the depth starts again
                    if (type.find("DepthClearCallback") != std::string::npos && hasLeaves(bin)
                        && (mTarget.mWindow || mTarget.mDepth))
                    {
                        // glClear with the camera's clear depth: 0 when the depth is reversed
                        clearRect(mArea, nullptr, true, false, SceneUtil::AutoDepth::isReversed() ? 0.f : 1.f, 0);
                        mFirstPersonCleared = true;
                        mSceneDepth = mTarget.mDepth;
                        firstPerson = true;
                    }
                }
                // what this bin holds is the first person model (drawGeometry)
                const bool wasFirstPerson = mInFirstPersonBin;
                mInFirstPersonBin = mInFirstPersonBin || firstPerson;
                auto child = bin.getRenderBinList().begin();
                for (; child != bin.getRenderBinList().end() && child->first < 0; ++child)
                    drawBin(*child->second, bins);
                for (const osgUtil::RenderLeaf* leaf : bin.getRenderLeafList())
                    drawLeaf(*leaf, bins);
                // The scene's opaque draws come sorted by state, whatever their distance: what is behind is shaded and
                // then covered. Nearest first ([Camera] draw nearest first), the GPU's depth test leaves out what the
                // nearer has covered. In bands of distance (two for each doubling), so that the draws of a band keep
                // their order by state and most of the binds they share.
                static const bool nearestFirst = settingBool("Camera", "draw nearest first", true);
                // (only the bin of the ordinary solid objects, which has no order of its own: the sky's layers are
                // drawn one over the other as they come)
                if (nearestFirst && mStageKind == StageKind::Scene && !mInFirstPersonBin && bin.getBinNum() == 0
                    && bin.getSortMode() == osgUtil::RenderBin::SORT_BY_STATE && bin.getStateGraphList().size() > 1)
                {
                    mNearestFirst.clear();
                    for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                        for (const auto& leaf : graph->_leaves)
                        {
                            const float depth = leaf->_depth;
                            const int band = depth > 1.f ? static_cast<int>(std::log2(depth) * 2.f) : 0;
                            mNearestFirst.emplace_back(band, leaf.get());
                        }
                    std::stable_sort(mNearestFirst.begin(), mNearestFirst.end(),
                        [](const auto& a, const auto& b) { return a.first < b.first; });
                    // (drawing a leaf can draw a bin of its own: the list is this bin's until then)
                    std::vector<std::pair<int, const osgUtil::RenderLeaf*>> leaves;
                    leaves.swap(mNearestFirst);
                    for (const auto& [band, leaf] : leaves)
                        drawLeaf(*leaf, bins);
                    leaves.swap(mNearestFirst);
                }
                else
                    for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                        for (const auto& leaf : graph->_leaves)
                            drawLeaf(*leaf, bins);
                for (; child != bin.getRenderBinList().end(); ++child)
                    drawBin(*child->second, bins);
                mInFirstPersonBin = wasFirstPerson;
                bins.pop_back();
            }

            // The state of a leaf's StateGraph with the bins' StateSets put in after its root's
            // Hashes of what a state set holds: the attributes, modes, textures, uniforms (by name) and defines; and the
            // same with the uniforms' objects too (objects). One pass for both: step() needs both for every state set
            // of every draw, every frame.
            static uint64_t fingerprint(const osg::StateSet& stateSet, uint64_t& objects)
            {
                uint64_t hash = 0xcbf29ce484222325ull;
                const auto mixInto = [](uint64_t& into, uint64_t value) {
                    into ^= value + 0x9e3779b97f4a7c15ull + (into << 6) + (into >> 2);
                };
                const auto mix = [&](uint64_t value) { mixInto(hash, value); };
                for (const auto& [key, ref] : stateSet.getAttributeList())
                {
                    mix(key.first);
                    mix(key.second);
                    mix(reinterpret_cast<uintptr_t>(ref.first.get()));
                    mix(ref.second);
                }
                for (const auto& [mode, value] : stateSet.getModeList())
                {
                    mix(mode);
                    mix(value);
                }
                const auto& textures = stateSet.getTextureAttributeList();
                for (size_t unit = 0; unit < textures.size(); ++unit)
                    for (const auto& [key, ref] : textures[unit])
                    {
                        mix(unit);
                        mix(key.first);
                        mix(reinterpret_cast<uintptr_t>(ref.first.get()));
                        mix(ref.second);
                    }
                for (const auto& [name, define] : stateSet.getDefineList())
                {
                    mix(std::hash<std::string>()(name));
                    mix(std::hash<std::string>()(define.first));
                    mix(define.second);
                }
                objects = hash;
                for (const auto& [name, ref] : stateSet.getUniformList())
                {
                    const uint64_t named = std::hash<std::string>()(name);
                    mix(named);
                    mix(ref.second);
                    mixInto(objects, named);
                    mixInto(objects, reinterpret_cast<uintptr_t>(ref.first.get()));
                    mixInto(objects, ref.second);
                }
                return hash;
            }

            // The state a state set (or, without one, a texture on unit 0) makes of another, kept from frame to frame
            // and found by what the state set holds but its uniforms' objects: a state set with other uniforms of the
            // same names gives the same state, marked volatile (its draws read them from their own state sets)
            Accumulated* step(Accumulated* parent, const osg::StateSet* stateSet, const osg::Texture* texture = nullptr)
            {
                if (stateSet == nullptr && texture == nullptr)
                    return parent;
                uint64_t objects = 0;
                uint64_t hash = stateSet != nullptr ? fingerprint(*stateSet, objects)
                                                    : reinterpret_cast<uintptr_t>(texture) * 0x9e3779b97f4a7c15ull + 0x7e47;
                if (stateSet == nullptr)
                    hash ^= 0x5bd1e9955bd1e995ull;
                Step& entry = mSteps[StepKey{ parent->mId, hash }];
                entry.mLastUse = mFrameCounter;
                if (entry.mState)
                {
                    if (!entry.mState->mVolatile && (entry.mSource != stateSet || entry.mObjects != objects))
                    {
                        entry.mState->mVolatile = true;
                        ++mStepsChanged;
                    }
                    return entry.mState.get();
                }
                ++mStepsNew;
                entry.mState = parent->copy();
                entry.mState->mId = ++mNextId;
                entry.mState->mDepth = parent->mDepth + 1;
                entry.mSource = stateSet;
                entry.mKeep = stateSet; // the uniforms the state points to stay
                entry.mObjects = objects;
                if (stateSet != nullptr)
                    entry.mState->apply(stateSet);
                else
                {
                    if (entry.mState->mTextures.empty())
                        entry.mState->mTextures.resize(1);
                    entry.mState->mTextures[0][{ osg::StateAttribute::TEXTURE, 0 }]
                        = { texture, osg::StateAttribute::ON };
                }
                return entry.mState.get();
            }

            // The state set at a level of the chain of the leaf being drawn (as accumulate put them together)
            const osg::StateSet* chainStateSet(uint32_t level) const
            {
                if (mChainGraph == nullptr || mChainState == nullptr)
                    return nullptr;
                int at = static_cast<int>(mChainState->mDepth) - 1;
                const osgUtil::StateGraph* graph = mChainGraph;
                for (; graph->_parent != nullptr; graph = graph->_parent)
                    if (const osg::StateSet* stateSet = graph->getStateSet())
                    {
                        if (at == static_cast<int>(level))
                            return stateSet;
                        if (--at < static_cast<int>(level))
                            return nullptr;
                    }
                // the root's state set, then the bins'
                int index = 0;
                if (const osg::StateSet* stateSet = graph->getStateSet())
                {
                    if (index == static_cast<int>(level))
                        return stateSet;
                    ++index;
                }
                if (mChainBins != nullptr)
                    for (const osgUtil::RenderBin* bin : *mChainBins)
                        if (const osg::StateSet* stateSet = bin->getStateSet())
                        {
                            if (index == static_cast<int>(level))
                                return stateSet;
                            ++index;
                        }
                return nullptr;
            }

            Accumulated* accumulate(const osgUtil::StateGraph* graph, const std::vector<const osgUtil::RenderBin*>& bins)
            {
                const auto key = std::make_pair(graph, bins.empty() ? nullptr : bins.back());
                // kept from frame to frame (no allocation once seen); valid only in the frame it was made in
                // (unordered_map's elements stay where they are as others are added)
                AccumulatedSlot& slot = mAccumulated[key];
                if (slot.mFrame == mFrameCounter)
                    return slot.mState;
                Accumulated* state;
                if (graph->_parent == nullptr)
                {
                    state = step(&mEmpty, graph->getStateSet());
                    for (const osgUtil::RenderBin* bin : bins)
                        state = step(state, bin->getStateSet());
                }
                else
                    state = step(accumulate(graph->_parent, bins), graph->getStateSet());
                slot.mState = state;
                slot.mFrame = mFrameCounter;
                return state;
            }

            // Where the water is seen (Android's occlusion queries over a grid of the screen): not done on Metal yet,
            // so the reflection draws everything (reflectionShows: nothing is known)
            static bool waterQueriesOn() { return false; }
            bool drawWaterInQueries(const osgUtil::RenderLeaf&, const osg::Geometry&, Accumulated&) { return false; }

            bool reflectionShows(const osgUtil::RenderLeaf& leaf, const osg::Drawable& drawable)
            {
                if (!mWaterKnown || leaf._modelview == nullptr || leaf._projection == nullptr)
                    return true;
                const osg::BoundingSphere& bound = drawable.getBound();
                if (!bound.valid())
                    return true;
                const osg::Matrixd& view = *leaf._modelview;
                const osg::Matrixd& projection = *leaf._projection;
                const osg::Vec3d centre = osg::Vec3d(bound.center()) * view;
                const double scale = std::sqrt(std::max({ osg::Vec3d(view(0, 0), view(0, 1), view(0, 2)).length2(),
                    osg::Vec3d(view(1, 0), view(1, 1), view(1, 2)).length2(),
                    osg::Vec3d(view(2, 0), view(2, 1), view(2, 2)).length2() }));
                const double radius = bound.radius() * scale;
                const double depth = -centre.z();
                // around the camera or behind it, or not a perspective view: no telling where it shows
                if (depth - radius <= 1.0 || projection(3, 3) != 0.0)
                    return true;
                if (!mWaterSeen)
                    return false;
                if (!mReflectObjects && std::strstr(typeid(drawable).name(), "TerrainDrawable") == nullptr)
                    return false;
                constexpr double margin = 0.15;
                const double reach = radius / (depth - radius) * 1.1;
                const double x = ((centre.x() * projection(0, 0) + centre.z() * projection(2, 0)) / depth) * 0.5 + 0.5;
                const double y = ((centre.y() * projection(1, 1) + centre.z() * projection(2, 1)) / depth) * 0.5 + 0.5;
                const double reachX = reach * std::abs(projection(0, 0)) * 0.5;
                const double reachY = reach * std::abs(projection(1, 1)) * 0.5;
                return x + reachX >= mWaterRect[0] - margin && x - reachX <= mWaterRect[2] + margin
                    && y + reachY >= mWaterRect[1] - margin && y - reachY <= mWaterRect[3] + margin;
            }

            void setChain(const osgUtil::StateGraph* graph, const std::vector<const osgUtil::RenderBin*>* bins,
                const Accumulated* state)
            {
                mChainGraph = graph;
                mChainBins = bins;
                mChainState = state;
            }

            // The state's program with its defines (looked up once for the state)
            Variant* variantOf(Accumulated& state)
            {
                const bool clip = state.mClipModes != 0;
                if (state.mVariant == nullptr || state.mVariantProgram != state.mProgram || state.mVariant->mClips != clip
                    || state.mVariantGeneration != mPrograms.sourceGeneration())
                {
                    state.mVariant
                        = mPrograms.variant(*state.mProgram, state.mDefines, *mState, clip, mWaitForPrograms);
                    state.mVariantProgram = state.mProgram;
                    state.mVariantGeneration = mPrograms.sourceGeneration();
                }
                return state.mVariant;
            }

            void noteUnknown(const osg::Drawable& drawable)
            {
                ++mSkipped;
                const std::string type = typeid(drawable).name();
                if (mUnknown.insert(type).second)
                    Log(Debug::Info) << "Metal: drawables of type " << type << " (" << drawable.className()
                                     << ") are not drawn yet";
            }

            void drawLeaf(const osgUtil::RenderLeaf& leaf, const std::vector<const osgUtil::RenderBin*>& bins)
            {
                drawLeafItself(leaf, bins);
                // as RenderLeaf::render: the main thread goes on (to change the scene graph) once the last dynamic
                // object is drawn, while the rest of the frame is
                if (leaf._dynamic && mState != nullptr && mState->getDynamicObjectCount() > 0)
                    mState->decrementDynamicObjectCount();
            }

            enum class DrawableKind
            {
                MyGui,
                CompositeMaps,
                Particles,
                Canvas,
                Compute, // run before the stage's pass (runCompute)
                Text, // osgText::Text (the statistics' text)
                Plain, // an osg::Drawable itself: a ReadImageFromFramebufferCallback's, else unknown
                Geometry,
                Unknown,
            };

            // What a drawable's class is drawn as (found once for each class)
            DrawableKind kindOf(const osg::Drawable& drawable)
            {
                const std::type_info& info = typeid(drawable);
                auto found = mDrawableKinds.find(&info);
                if (found != mDrawableKinds.end())
                    return found->second;
                const std::string type = info.name();
                DrawableKind kind = DrawableKind::Unknown;
                if (std::strcmp(drawable.libraryName(), "osgMyGUI") == 0
                    && std::strcmp(drawable.className(), "Drawable") == 0)
                    kind = DrawableKind::MyGui;
                else if (std::strcmp(drawable.className(), "CompositeMapRenderer") == 0
                    || type.find("CompositeMapRenderer") != std::string::npos)
                    kind = DrawableKind::CompositeMaps;
                else if (dynamic_cast<const osgParticle::ParticleSystem*>(&drawable) != nullptr)
                    kind = DrawableKind::Particles;
                else if (type.find("PingPongCanvas") != std::string::npos)
                    kind = DrawableKind::Canvas;
                else if (dynamic_cast<const osg::DispatchCompute*>(&drawable) != nullptr)
                    kind = DrawableKind::Compute;
                else if (dynamic_cast<const osgText::Text*>(&drawable) != nullptr)
                    kind = DrawableKind::Text;
                else if (type == "N3osg8DrawableE")
                    kind = DrawableKind::Plain;
                else if (drawable.asGeometry() != nullptr && type.find("RipplesSurface") == std::string::npos)
                    kind = DrawableKind::Geometry;
                mDrawableKinds.emplace(&info, kind);
                return kind;
            }

            void drawLeafItself(const osgUtil::RenderLeaf& leaf, const std::vector<const osgUtil::RenderBin*>& bins)
            {
                const osg::Drawable* drawable = leaf.getDrawable();
                if (drawable == nullptr || leaf._parent == nullptr)
                    return;
                switch (kindOf(*drawable))
                {
                    case DrawableKind::MyGui:
                        drawMyGui(static_cast<const MyGuiDrawable&>(*drawable), leaf, bins);
                        return;
                    case DrawableKind::CompositeMaps:
                        mCompositeRenderer = static_cast<const CompositeMapQueues*>(drawable);
                        return;
                    case DrawableKind::Particles:
                        drawParticles(static_cast<const osgParticle::ParticleSystem&>(*drawable), leaf, bins);
                        return;
                    case DrawableKind::Canvas:
                        drawCanvas(leaf, bins);
                        return;
                    case DrawableKind::Compute:
                        return;
                    case DrawableKind::Text:
                        drawText(static_cast<const osgText::Text&>(*drawable), leaf, bins);
                        return;
                    case DrawableKind::Plain:
                        if (const osg::Drawable::DrawCallback* callback = drawable->getDrawCallback();
                            callback != nullptr
                            && std::strstr(typeid(*callback).name(), "ReadImageFromFramebufferCallback") != nullptr)
                        {
                            const auto& read = static_cast<const ReadImageCallback&>(*callback);
                            if (read.mImage)
                                mReads.push_back({ read.mImage, read.mWidth, read.mHeight });
                            return;
                        }
                        noteUnknown(*drawable);
                        return;
                    case DrawableKind::Unknown:
                        noteUnknown(*drawable);
                        return;
                    case DrawableKind::Geometry:
                        break;
                }
                if (mStageKind == StageKind::Reflection && waterQueriesOn())
                {
                    if (!reflectionShows(leaf, *drawable))
                    {
                        ++mReflectionCulled;
                        return;
                    }
                    ++mReflectionKept;
                }
                Accumulated* state = accumulate(leaf._parent, bins);
                setChain(leaf._parent, &bins, state);
                const auto& geometry = static_cast<const osg::Geometry&>(*drawable);
                if (mStageKind != StageKind::Scene || geometry.getName() != "Water Geometry"
                    || !drawWaterInQueries(leaf, geometry, *state))
                    drawGeometry(geometry, *state, leaf._modelview.get(), leaf._projection.get());
                setChain(nullptr, nullptr, nullptr);
            }

            // The state's uniforms and images for the program, made again for each frame and target
            DrawTemplate* templateFor(Accumulated& state, Variant& variant)
            {
                DrawTemplate* draw = nullptr;
                for (auto& existing : state.mTemplates)
                    if (existing->mVariant == &variant)
                        draw = existing.get();
                if (draw == nullptr)
                {
                    state.mTemplates.push_back(makeTemplate(state, variant));
                    draw = state.mTemplates.back().get();
                }
                if (draw->mFrame != mFrameCounter || draw->mTargetColor != mTarget.mColor
                    || draw->mTargetDepth != mTarget.mDepth)
                    refresh(*draw);
                return draw;
            }

            // Where each uniform and sampler element of the program comes from in the state
            std::unique_ptr<DrawTemplate> makeTemplate(const Accumulated& state, Variant& variant)
            {
                auto made = std::make_unique<DrawTemplate>();
                DrawTemplate& draw = *made;
                draw.mVariant = &variant;
                const UniformBlock* blocks[3]
                    = { &variant.mVertexUniforms, &variant.mFragmentUniforms, &variant.mLightUniforms };
                for (int stage = 0; stage < 3; ++stage)
                {
                    std::vector<uint8_t>& bytes = draw.mBase[stage];
                    bytes.assign(blocks[stage]->mSize, 0);
                    for (const UniformMember& member : blocks[stage]->mMembers)
                    {
                        const int builtin = builtinIndex(member.mName);
                        if (builtin >= 0)
                        {
                            draw.mPatches.push_back({ stage, member.mOffset, builtin });
                            continue;
                        }
                        auto found = state.mUniforms.find(member.mName);
                        if (found != state.mUniforms.end() && found->second.mUniform != nullptr)
                        {
                            draw.mSources.push_back({ stage, &member, found->second.mUniform, found->second.mLevel,
                                found->second.mOwner });
                            continue;
                        }
                        auto initializer = variant.mInitializers.find(member.mName);
                        if (initializer != variant.mInitializers.end())
                        {
                            const std::vector<float>& values = initializer->second.values;
                            writeMember(bytes.data(), blocks[stage]->mSize, member, 1,
                                static_cast<uint32_t>(values.size()), [&](uint32_t index) -> double {
                                    return index < values.size() ? values[index] : 0.0;
                                });
                        }
                    }
                }

                // the samplers' units: a uniform of the state, else the shader manager's program, else 0
                const SamplerProgram* samplerProgram = nullptr;
                if (state.mProgram != nullptr && std::strcmp(state.mProgram->className(), "SamplerProgram") == 0
                    && std::strcmp(state.mProgram->libraryName(), "Shader") == 0)
                    samplerProgram = static_cast<const SamplerProgram*>(state.mProgram);
                for (const SamplerSlot& slot : variant.mSamplers)
                    for (uint32_t element = 0; element < slot.mCount; ++element)
                    {
                        int unit = -1;
                        if (const osg::Uniform* uniform = state.uniform(slot.mName))
                            uniform->getElement(element, unit);
                        if (unit < 0 && samplerProgram != nullptr)
                            for (const auto& sampler : samplerProgram->mSamplers)
                                if (sampler->getName() == slot.mName)
                                    sampler->getElement(element, unit);
                        if (unit < 0 && slot.mName == "a3PointShadowMap")
                            unit = pointShadowTextureUnit();
                        if (unit < 0)
                            unit = static_cast<int>(element);
                        draw.mImageSources.push_back(
                            { state.texture(static_cast<unsigned>(unit)), slot.mShadow, slot.mViewType });
                    }
                return made;
            }

            void refresh(DrawTemplate& draw)
            {
                draw.mFrame = mFrameCounter;
                draw.mTargetColor = mTarget.mColor;
                draw.mTargetDepth = mTarget.mDepth;
                draw.mStatic[0] = draw.mStatic[1] = draw.mStatic[2] = nil;
                const Variant& variant = *draw.mVariant;
                const UniformBlock* blocks[3]
                    = { &variant.mVertexUniforms, &variant.mFragmentUniforms, &variant.mLightUniforms };
                // the uniforms' values: each written again only when it changed (OSG counts each change). Volatile
                // ones are written for each draw from its own state sets instead.
                if (!draw.mWritten || draw.mStamps.size() != draw.mSources.size())
                {
                    for (int stage = 0; stage < 3; ++stage)
                        draw.mBlocks[stage] = draw.mBase[stage];
                    draw.mStamps.assign(draw.mSources.size(), ~0u);
                    draw.mWritten = true;
                }
                for (size_t i = 0; i < draw.mSources.size(); ++i)
                {
                    const DrawTemplate::Source& source = draw.mSources[i];
                    if (source.mOwner->mVolatile && draw.mStamps[i] != ~0u)
                        continue;
                    const unsigned count = source.mUniform->getModifiedCount();
                    if (count == draw.mStamps[i])
                        continue;
                    draw.mStamps[i] = count;
                    writeUniform(draw.mBlocks[source.mStage].data(), blocks[source.mStage]->mSize, *source.mMember,
                        *source.mUniform);
                }
                draw.mVolatile.clear();
                for (const DrawTemplate::Source& source : draw.mSources)
                    if (source.mOwner->mVolatile)
                        draw.mVolatile.push_back(&source);
                draw.mImages.clear();
                for (const DrawTemplate::ImageSource& source : draw.mImageSources)
                {
                    const osg::Texture* texture = source.mTexture;
                    id<MTLSamplerState> sampler = nil;
                    Image* image = texture ? mResources.texture(*texture, source.mShadow, sampler) : nullptr;
                    // a texture the pass draws into is not read in it
                    if (image != nullptr && (image == mTarget.mColor || image == mTarget.mDepth))
                        image = nullptr;
                    // a shadow sampler needs a depth image (a depth image read as plain values, the opaque depth
                    // copy, is fine)
                    if (image != nullptr && source.mShadow && !image->mDepthAspect)
                        image = nullptr;
                    const bool dummy = image == nullptr;
                    if (dummy && texture != nullptr && mDummyLogged.size() < 40 && mDummyLogged.insert(texture).second)
                    {
                        auto& mutableTexture = const_cast<osg::Texture&>(*texture);
                        const osg::Image* first = mutableTexture.getNumImages() ? mutableTexture.getImage(0) : nullptr;
                        Log(Debug::Verbose) << "Metal: texture drawn as the dummy: " << texture->className() << " '"
                                            << texture->getName() << "' image "
                                            << (first ? first->getFileName() : std::string("none")) << " "
                                            << (first ? first->s() : 0) << "x" << (first ? first->t() : 0)
                                            << " format 0x" << std::hex << (first ? first->getPixelFormat() : 0)
                                            << std::dec << " data " << (first && first->data() ? "yes" : "no")
                                            << " shadow " << source.mShadow << " texture " << texture->getTextureWidth()
                                            << "x" << texture->getTextureHeight() << " (" << texture << ")";
                    }
                    if (dummy)
                        image = mResources.dummy(source.mShadow);
                    DrawTemplate::Bound bound;
                    bound.mSampler = dummy ? mResources.sampler(nullptr, source.mShadow) : sampler;
                    bound.mTexture = image ? mResources.view(*image, source.mViewType) : nil;
                    draw.mImages.push_back(bound);
                }
            }

            static bool sameImages(const std::vector<DrawTemplate::Bound>& a, const std::vector<DrawTemplate::Bound>& b)
            {
                if (a.size() != b.size())
                    return false;
                for (size_t i = 0; i < a.size(); ++i)
                    if (a[i].mTexture != b[i].mTexture || a[i].mSampler != b[i].mSampler)
                        return false;
                return true;
            }

            // A buffer in a stage's argument table (0 vertex, 1 fragment), set only when it changed
            void bindBuffer(int stage, uint32_t slot, id<MTLBuffer> buffer, uint64_t offset)
            {
                if (slot >= sBufferSlots)
                    return;
                BoundBuffer& bound = mBoundBuffers[stage][slot];
                if (bound.mBuffer == buffer)
                {
                    if (bound.mOffset == offset)
                        return;
                    if (stage == 0)
                        [mEncoder setVertexBufferOffset:offset atIndex:slot];
                    else
                        [mEncoder setFragmentBufferOffset:offset atIndex:slot];
                }
                else if (stage == 0)
                    [mEncoder setVertexBuffer:buffer offset:offset atIndex:slot];
                else
                    [mEncoder setFragmentBuffer:buffer offset:offset atIndex:slot];
                bound.mBuffer = buffer;
                bound.mOffset = offset;
            }

            void bindTexture(int stage, uint32_t slot, id<MTLTexture> texture, id<MTLSamplerState> sampler)
            {
                if (slot < sTextureSlots)
                {
                    BoundTexture& bound = mBoundTextures[stage][slot];
                    if (bound.mTexture == texture && bound.mSampler == sampler)
                        return;
                    bound.mTexture = texture;
                    bound.mSampler = sampler;
                }
                if (stage == 0)
                {
                    [mEncoder setVertexTexture:texture atIndex:slot];
                    [mEncoder setVertexSamplerState:sampler atIndex:slot];
                }
                else
                {
                    [mEncoder setFragmentTexture:texture atIndex:slot];
                    [mEncoder setFragmentSamplerState:sampler atIndex:slot];
                }
            }

            static constexpr uint32_t sBufferSlots = 31, sTextureSlots = 32;
            struct BoundBuffer
            {
                id<MTLBuffer> mBuffer = nil;
                uint64_t mOffset = 0;
            };
            struct BoundTexture
            {
                id<MTLTexture> mTexture = nil;
                id<MTLSamplerState> mSampler = nil;
            };
            BoundBuffer mBoundBuffers[2][sBufferSlots];
            BoundTexture mBoundTextures[2][sTextureSlots];

            // Binds the program's buffers and textures: the state's uniforms (sent once a frame, or once for each set
            // of volatile state sets), the draw's matrices and the samplers' textures
            bool bindUniforms(DrawTemplate& draw, const osg::RefMatrix* modelView, const osg::RefMatrix* projection,
                uint32_t clipModes)
            {
                Variant& variant = *draw.mVariant;
                if (mEncoder == nil)
                    return false;
                const uint64_t alignment = 16;
                std::array<id<MTLBuffer>, 4> buffers{};
                uint64_t bufferOffsets[4]{};

                // the state's blocks (vertex, fragment, lights at bindings 0, 1 and 3): sent once a frame, but for
                // those with volatile uniforms (the light list's), sent for each set of them. A lights block is
                // shared by every draw of the frame with the same light uniforms.
                static constexpr uint32_t bindingOf[3] = { 0, 1, 3 };
                for (int stage = 0; stage < 3; ++stage)
                {
                    const std::vector<uint8_t>& bytes = draw.mBlocks[stage];
                    if (bytes.empty() || (stage == 2 && variant.mLightStages == 0))
                        continue;
                    const uint32_t binding = bindingOf[stage];
                    bool hasVolatile = false;
                    for (const DrawTemplate::Source* source : draw.mVolatile)
                        hasVolatile |= source->mStage == stage;
                    if (!hasVolatile)
                    {
                        if (draw.mStatic[stage] == nil)
                        {
                            Span span = mResources.stream(bytes.size(), alignment);
                            if (span.mData == nullptr)
                                return false;
                            std::memcpy(span.mData, bytes.data(), bytes.size());
                            draw.mStatic[stage] = span.mBuffer;
                            draw.mStaticOffset[stage] = span.mOffset;
                        }
                        buffers[binding] = draw.mStatic[stage];
                        bufferOffsets[binding] = draw.mStaticOffset[stage];
                        continue;
                    }
                    // what the block is made of: the template's uniforms (for a block with others than the volatile
                    // ones) and the draw's state sets the volatile ones come from (their uniforms stay as they are
                    // for the frame)
                    uint64_t key = 0xcbf29ce484222325ull;
                    const auto mix = [&](uint64_t value) { key = (key ^ value) * 0x100000001b3ull; };
                    mix(reinterpret_cast<uintptr_t>(&variant));
                    mix(static_cast<uint64_t>(stage));
                    bool onlyVolatile = stage == 2;
                    uint32_t lastLevel = ~0u;
                    for (const DrawTemplate::Source& source : draw.mSources)
                    {
                        if (source.mStage != stage)
                            continue;
                        if (!source.mOwner->mVolatile)
                        {
                            onlyVolatile = false;
                            continue;
                        }
                        if (source.mLevel == lastLevel)
                            continue;
                        lastLevel = source.mLevel;
                        mix(reinterpret_cast<uintptr_t>(chainStateSet(source.mLevel)));
                    }
                    if (!onlyVolatile)
                        mix(reinterpret_cast<uintptr_t>(&draw));
                    SharedBlock& shared = mSharedBlocks[key];
                    if (shared.mFrame != mFrameCounter)
                    {
                        Span span = mResources.stream(bytes.size(), alignment);
                        if (span.mData == nullptr)
                            return false;
                        std::memcpy(span.mData, bytes.data(), bytes.size());
                        for (const DrawTemplate::Source& source : draw.mSources)
                        {
                            if (source.mStage != stage || !source.mOwner->mVolatile)
                                continue;
                            const osg::StateSet* stateSet = chainStateSet(source.mLevel);
                            const osg::Uniform* uniform
                                = stateSet ? stateSet->getUniform(source.mMember->mName) : nullptr;
                            if (uniform != nullptr)
                                writeUniform(span.mData, static_cast<uint32_t>(bytes.size()), *source.mMember, *uniform);
                        }
                        shared = { span.mBuffer, span.mOffset, mFrameCounter };
                    }
                    buffers[binding] = shared.mBuffer;
                    bufferOffsets[binding] = shared.mOffset;
                }

                // the draw's matrices: model view, projection, their product and the normal matrix (OSG's,
                // State::applyModelViewMatrix, its columns padded to vec4)
                if (variant.mDrawStages != 0)
                {
                    Span span = mResources.stream(sDrawBlockSize, alignment);
                    if (span.mData == nullptr)
                        return false;
                    const osg::Matrix modelViewMatrix = modelView ? osg::Matrix(*modelView) : osg::Matrix();
                    const osg::Matrix projectionMatrix = projection ? osg::Matrix(*projection) : osg::Matrix();
                    const osg::Matrixf matrices[3] = { osg::Matrixf(modelViewMatrix), osg::Matrixf(projectionMatrix),
                        osg::Matrixf(modelViewMatrix * projectionMatrix) };
                    std::memcpy(span.mData, matrices, sizeof(matrices));
                    // the inverse of the upper 3x3, column c of the inverse as column c (OSG builds its Matrix3 that way)
                    const osg::Matrix& m = modelViewMatrix;
                    const double c00 = m(1, 1) * m(2, 2) - m(1, 2) * m(2, 1);
                    const double c01 = m(1, 2) * m(2, 0) - m(1, 0) * m(2, 2);
                    const double c02 = m(1, 0) * m(2, 1) - m(1, 1) * m(2, 0);
                    const double det = m(0, 0) * c00 + m(0, 1) * c01 + m(0, 2) * c02;
                    const double invDet = std::abs(det) > 1e-30 ? 1.0 / det : 0.0;
                    double inverse[3][3];
                    inverse[0][0] = c00 * invDet;
                    inverse[0][1] = (m(0, 2) * m(2, 1) - m(0, 1) * m(2, 2)) * invDet;
                    inverse[0][2] = (m(0, 1) * m(1, 2) - m(0, 2) * m(1, 1)) * invDet;
                    inverse[1][0] = c01 * invDet;
                    inverse[1][1] = (m(0, 0) * m(2, 2) - m(0, 2) * m(2, 0)) * invDet;
                    inverse[1][2] = (m(0, 2) * m(1, 0) - m(0, 0) * m(1, 2)) * invDet;
                    inverse[2][0] = c02 * invDet;
                    inverse[2][1] = (m(0, 1) * m(2, 0) - m(0, 0) * m(2, 1)) * invDet;
                    inverse[2][2] = (m(0, 0) * m(1, 1) - m(0, 1) * m(1, 0)) * invDet;
                    float normal[12]{};
                    for (int c = 0; c < 3; ++c)
                        for (int r = 0; r < 3; ++r)
                            normal[c * 4 + r] = static_cast<float>(inverse[r][c]);
                    std::memcpy(span.mData + 192, normal, sizeof(normal));
                    if (clipModes != 0)
                        ++mClippedDraws;
                    // the clip planes (eye space) the state turns on; one that is off clips nothing
                    for (int i = 0; i < 2; ++i)
                    {
                        const osg::Vec4f plane
                            = (clipModes & (1u << i)) ? mClipPlanes[i] : osg::Vec4f(0.f, 0.f, 0.f, 1.f);
                        std::memcpy(span.mData + 240 + i * 16, plane.ptr(), 16);
                    }
                    if (variant.mInverseModelView)
                    {
                        const osg::Matrixf inverseModelView(osg::Matrix::inverse(modelViewMatrix));
                        std::memcpy(span.mData + 272, inverseModelView.ptr(), 64);
                    }
                    buffers[2] = span.mBuffer;
                    bufferOffsets[2] = span.mOffset;
                }
                // Vulkan's bindings 0 to 3 in Metal's slots: the stage's own block, the matrices, the lights
                if (buffers[0] != nil)
                    bindBuffer(0, sStageBlockSlot, buffers[0], bufferOffsets[0]);
                if (buffers[1] != nil)
                    bindBuffer(1, sStageBlockSlot, buffers[1], bufferOffsets[1]);
                for (int stage = 0; stage < 2; ++stage)
                {
                    const VkShaderStageFlags bit = stage == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
                    if (buffers[2] != nil && (variant.mDrawStages & bit))
                        bindBuffer(stage, sDrawBlockSlot, buffers[2], bufferOffsets[2]);
                    if (buffers[3] != nil && (variant.mLightStages & bit))
                        bindBuffer(stage, sLightBlockSlot, buffers[3], bufferOffsets[3]);
                }
                ++mSetBinds;
                if (!variant.mStorage.empty() && !bindStorage(variant))
                    return false;
                // the samplers' textures, each element in its stages' slots
                size_t image = 0;
                for (const SamplerSlot& slot : variant.mSamplers)
                {
                    for (uint32_t element = 0; element < slot.mCount && image + element < draw.mImages.size(); ++element)
                    {
                        const DrawTemplate::Bound& bound = draw.mImages[image + element];
                        for (int stage = 0; stage < 2; ++stage)
                            if (slot.mSlot[stage] >= 0)
                                bindTexture(stage, static_cast<uint32_t>(slot.mSlot[stage]) + element, bound.mTexture,
                                    bound.mSampler);
                    }
                    image += slot.mCount;
                }
                if (sameImages(draw.mImages, mLastImages))
                    ++mSameImageBinds;
                else
                    mLastImages = draw.mImages;
                mUniformBytes += variant.mVertexUniforms.mSize + variant.mFragmentUniforms.mSize
                    + (variant.mLightStages ? variant.mLightUniforms.mSize : 0) + (variant.mDrawStages ? sDrawBlockSize : 0);
                return true;
            }

            // The pipeline state of a leaf drawn into the current target
            PipelineState pipelineState(const Accumulated& state, VkPrimitiveTopology topology) const
            {
                PipelineState fixed = state.mFixed;
                fixed.mTopology = topology;
                fixed.mFlipY = mFlip ? 1 : 0;
                // (Metal's winding is that of its normalised device coordinates, y up as OpenGL's: turned over with y)
                fixed.mFrontFace
                    = (state.mGlCounterClockwise != mFlip) ? VK_FRONT_FACE_COUNTER_CLOCKWISE : VK_FRONT_FACE_CLOCKWISE;
                fixed.mColorAttachments = (mTarget.mWindow || mTarget.mColor) ? (mTarget.mColor1 ? 2 : 1) : 0;
                fixed.mColorFormat = mTarget.mColorFormat;
                fixed.mColorFormat1 = mTarget.mColor1 ? mTarget.mColor1->mFormat : VK_FORMAT_UNDEFINED;
                fixed.mDepthFormat = mTarget.mDepthFormat;
                if (!mTarget.mWindow && mTarget.mDepth == nullptr)
                {
                    fixed.mDepthTest = fixed.mDepthWrite = 0;
                    fixed.mStencil = 0;
                }
                if (fixed.mStencil && !hasStencil(mTarget.mDepthFormat))
                    fixed.mStencil = 0;
                return fixed;
            }

            // A geometry's vertex inputs and index buffers for a program, kept from frame to frame while its arrays
            // stay the same (made again each frame where one is streamed), and the pipelines it was last drawn with
            struct GeometryEntry
            {
                osg::ref_ptr<const osg::Geometry> mGeometry;
                const Variant* mVariant = nullptr;
                uint64_t mBuilt = ~0ull; // the frame it was made or last checked in
                uint64_t mVersion = 0; // counts the times it was made
                uint64_t mMade = 0; // the frame it was made in
                uint64_t mLastUse = 0;
                bool mStreamed = false;
                bool mValid = false;
                VertexLayout mLayout;
                std::vector<id<MTLBuffer>> mBuffers;
                std::vector<NSUInteger> mOffsets;
                std::vector<std::pair<const osg::Array*, unsigned>> mArrays; // what the inputs read, and when
                struct Primitive
                {
                    const osg::PrimitiveSet* mSet;
                    unsigned mModified;
                    VkPrimitiveTopology mTopology;
                    id<MTLBuffer> mIndexBuffer;
                    uint64_t mIndexOffset;
                    VkIndexType mIndexType;
                    uint32_t mIndexCount; // converted indices' count
                    bool mConverted;
                };
                std::vector<Primitive> mPrimitives;
                struct Pipelines
                {
                    bool mUsed = false;
                    PipelineState mFixed;
                    std::vector<id<MTLRenderPipelineState>> mPerPrimitive;
                    uint64_t mGeneration = 0; // Programs::pipelineGeneration when one still being made was asked for
                    bool mWaiting = false; // one of them is still being made
                };
                std::array<Pipelines, 4> mPipelines;
                unsigned mNextPipelines = 0;
            };

            // Which of the geometry's arrays an input reads
            static const osg::Array* inputArray(const osg::Geometry& geometry, const VertexInput& input,
                const osg::Program::AttribBindingList& bindings)
            {
                switch (input.mKind)
                {
                    case VertexInput::Vertex:
                        return geometry.getVertexArray();
                    case VertexInput::Normal:
                        return geometry.getNormalArray();
                    case VertexInput::Color:
                        return geometry.getColorArray();
                    case VertexInput::SecondaryColor:
                        return geometry.getSecondaryColorArray();
                    case VertexInput::FogCoord:
                        return geometry.getFogCoordArray();
                    case VertexInput::TexCoord:
                        return geometry.getTexCoordArray(input.mUnit);
                    case VertexInput::Unknown:
                        break;
                    case VertexInput::Generic:
                    {
                        auto found = bindings.find(input.mName);
                        return found != bindings.end() ? geometry.getVertexAttribArray(found->second) : nullptr;
                    }
                }
                auto found = bindings.find(input.mName);
                return found != bindings.end() ? geometry.getVertexAttribArray(found->second) : nullptr;
            }

            // The buffer of a constant vertex input (OpenGL's current value: white for the color, (0, 0, 0, 1)
            // otherwise)
            bool constantInput(const VertexInput& input, VkFormat& format, __strong id<MTLBuffer>& buffer, uint64_t& offset)
            {
                if (!mConstants[0])
                {
                    mConstants[0] = new osg::Vec4Array;
                    mConstants[0]->push_back(osg::Vec4(1.f, 1.f, 1.f, 1.f));
                    mConstants[1] = new osg::Vec4Array;
                    mConstants[1]->push_back(osg::Vec4(0.f, 0.f, 0.f, 1.f));
                    mConstants[2] = new osg::Vec4Array;
                    mConstants[2]->push_back(osg::Vec4(0.f, 0.f, 0.f, 0.f));
                }
                const int which = input.mInteger ? 2 : input.mName == "osg_Color" ? 0 : 1;
                format = input.mInteger ? VK_FORMAT_R32G32B32A32_SINT : VK_FORMAT_R32G32B32A32_SFLOAT;
                return mResources.buffer(*mConstants[which], buffer, offset);
            }

            bool stillValid(const GeometryEntry& entry, const osg::Geometry& geometry, const Variant& variant,
                const osg::Program::AttribBindingList& bindings) const
            {
                if (!entry.mValid || entry.mStreamed || entry.mVariant != &variant)
                    return false;
                for (size_t i = 0; i < variant.mInputs.size(); ++i)
                {
                    const osg::Array* array = inputArray(geometry, variant.mInputs[i], bindings);
                    const auto& [was, modified] = entry.mArrays[i];
                    if (array != was || (array != nullptr && array->getModifiedCount() != modified))
                        return false;
                }
                const osg::Geometry::PrimitiveSetList& sets = geometry.getPrimitiveSetList();
                size_t at = 0;
                for (const auto& set : sets)
                {
                    if (set->getNumIndices() == 0 || set->getMode() == 0)
                        continue;
                    if (at >= entry.mPrimitives.size() || entry.mPrimitives[at].mSet != set.get()
                        || entry.mPrimitives[at].mModified != set->getModifiedCount())
                        return false;
                    ++at;
                }
                return at == entry.mPrimitives.size();
            }

            void buildEntry(GeometryEntry& entry, const osg::Geometry& geometry, const Variant& variant,
                const osg::Program::AttribBindingList& bindings)
            {
                entry.mValid = false;
                entry.mStreamed = false;
                entry.mVariant = &variant;
                entry.mVersion = ++mGeometryVersions;
                entry.mLayout.mBindings.clear();
                entry.mLayout.mAttributes.clear();
                entry.mBuffers.clear();
                entry.mOffsets.clear();
                entry.mArrays.clear();
                entry.mPrimitives.clear();
                for (auto& pipelines : entry.mPipelines)
                    pipelines.mUsed = false;
                for (const VertexInput& input : variant.mInputs)
                {
                    const osg::Array* array = inputArray(geometry, input, bindings);
                    entry.mArrays.emplace_back(array, array ? array->getModifiedCount() : 0u);
                    const uint32_t binding = static_cast<uint32_t>(entry.mLayout.mBindings.size());
                    VkFormat format = VK_FORMAT_UNDEFINED;
                    id<MTLBuffer> buffer = nil;
                    uint64_t offset = 0;
                    uint32_t stride = 0;
                    if (array != nullptr && array->getBinding() != osg::Array::BIND_OFF
                        && array->getNumElements() > 0)
                    {
                        // normals and colours in bytes ([Video] packed vertex data): 4 for each vertex, not 12 or 16
                        static const bool pack = settingBool("Video", "packed vertex data", true);
                        const bool perVertex = array->getBinding() == osg::Array::BIND_PER_VERTEX
                            || array->getBinding() == osg::Array::BIND_UNDEFINED;
                        const bool direction = input.mKind == VertexInput::Normal;
                        if (pack && perVertex && !input.mInteger
                            && (direction || input.mKind == VertexInput::Color
                                || input.mKind == VertexInput::SecondaryColor)
                            && mResources.packedArray(*array, direction, buffer, offset))
                        {
                            format = direction ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8B8A8_UNORM;
                            stride = 4;
                        }
                        else if ((format = vertexFormat(*array, input.mInteger,
                                      input.mKind == VertexInput::Color || input.mKind == VertexInput::SecondaryColor
                                          || input.mKind == VertexInput::Normal))
                                     != VK_FORMAT_UNDEFINED
                                 && mResources.buffer(*array, buffer, offset, &entry.mStreamed))
                            stride = array->getBinding() == osg::Array::BIND_PER_VERTEX
                                    || array->getBinding() == osg::Array::BIND_UNDEFINED
                                ? array->getElementSize()
                                : 0;
                        else
                            format = VK_FORMAT_UNDEFINED;
                        // Metal reads no scaled integers as floats (USCALED, SSCALED): the input's constant instead
                        if (format != VK_FORMAT_UNDEFINED && mtlVertexFormat(format) == MTLVertexFormatInvalid)
                        {
                            if (mFormatsLogged.insert(format).second)
                                Log(Debug::Warning) << "Metal: vertex format " << format << " (" << input.mName
                                                    << ") is not read by Metal; its constant is used";
                            format = VK_FORMAT_UNDEFINED;
                        }
                        // Metal's strides are multiples of 4: an array of 3 shorts or bytes spread to 8 or 4
                        else if (format != VK_FORMAT_UNDEFINED && stride % 4 != 0)
                        {
                            const uint32_t aligned = (stride + 3) & ~3u;
                            if (mResources.alignedArray(*array, aligned, buffer, offset))
                                stride = aligned;
                            else
                                format = VK_FORMAT_UNDEFINED;
                        }
                    }
                    if (format == VK_FORMAT_UNDEFINED && !constantInput(input, format, buffer, offset))
                        return;
                    entry.mLayout.mBindings.push_back({ binding, stride, VK_VERTEX_INPUT_RATE_VERTEX });
                    entry.mLayout.mAttributes.push_back({ input.mLocation, binding, format, 0 });
                    entry.mBuffers.push_back(buffer);
                    entry.mOffsets.push_back(offset);
                }
                for (const auto& primitiveSet : geometry.getPrimitiveSetList())
                {
                    const osg::PrimitiveSet& primitives = *primitiveSet;
                    const unsigned mode = primitives.getMode();
                    if (primitives.getNumIndices() == 0 || mode == 0) // points need gl_PointSize, not written
                        continue;
                    GeometryEntry::Primitive out{};
                    out.mSet = &primitives;
                    out.mModified = primitives.getModifiedCount();
                    bool convert = false;
                    out.mTopology = topologyOf(mode, convert);
                    out.mIndexType = VK_INDEX_TYPE_UINT16;
                    const osg::DrawElements* elements = primitives.getDrawElements();
                    const bool ubyte = primitives.getType() == osg::PrimitiveSet::DrawElementsUBytePrimitiveType;
                    if (elements != nullptr && !convert && !ubyte)
                    {
                        if (!mResources.buffer(*elements, out.mIndexBuffer, out.mIndexOffset, &entry.mStreamed))
                            out.mIndexBuffer = nil;
                        out.mIndexType = primitives.getType() == osg::PrimitiveSet::DrawElementsUIntPrimitiveType
                            ? VK_INDEX_TYPE_UINT32
                            : VK_INDEX_TYPE_UINT16;
                    }
                    else if (elements != nullptr || convert)
                    {
                        const auto converter = [&](std::vector<uint32_t>& converted) {
                            std::vector<uint32_t> in(primitives.getNumIndices());
                            for (unsigned i = 0; i < in.size(); ++i)
                                in[i] = primitives.index(i);
                            convertIndices(mode, in, converted);
                        };
                        out.mConverted = true;
                        if (!mResources.convertedIndices(primitives, mode, converter, out.mIndexBuffer,
                                out.mIndexOffset, out.mIndexCount))
                            out.mIndexBuffer = nil;
                        out.mIndexType = VK_INDEX_TYPE_UINT32;
                    }
                    entry.mPrimitives.push_back(out);
                }
                entry.mValid = true;
            }

            GeometryEntry* geometryEntry(const osg::Geometry& geometry, const Variant& variant,
                const osg::Program::AttribBindingList& bindings)
            {
                GeometryEntry& entry = mGeometries[{ &geometry, &variant }];
                entry.mLastUse = mFrameCounter;
                if (entry.mGeometry.get() != &geometry)
                {
                    entry = GeometryEntry{};
                    entry.mGeometry = &geometry;
                }
                // checked already this frame for this program
                if (entry.mBuilt == mFrameCounter && entry.mVariant == &variant && entry.mValid)
                    return &entry;
                // made again now and then even when nothing changed: Resources lets go of buffers not asked for in
                // 3600 frames, and the entry's handles are only asked for when it is made (after 1000 to 2023 frames,
                // spread by the geometry so that the ones made together are not all made again in one frame)
                const uint64_t keep = 1000 + (reinterpret_cast<uintptr_t>(&geometry) >> 4) % 1024;
                if (!stillValid(entry, geometry, variant, bindings) || mFrameCounter - entry.mMade > keep)
                {
                    buildEntry(entry, geometry, variant, bindings);
                    entry.mMade = mFrameCounter;
                }
                entry.mBuilt = mFrameCounter;
                return entry.mValid ? &entry : nullptr;
            }

            // What is bound on the encoder: forgotten at each new encoder and after draws of the translator's own
            void forgetBindings()
            {
                mBoundStorageKey = 0;
                mBoundPipeline = nil;
                mBoundVertices = nullptr;
                for (auto& stage : mBoundBuffers)
                    for (BoundBuffer& bound : stage)
                        bound = BoundBuffer{};
                for (auto& stage : mBoundTextures)
                    for (BoundTexture& bound : stage)
                        bound = BoundTexture{};
                mEncoderStateKnown = false;
            }

            static MTLPrimitiveType primitiveOf(uint32_t topology)
            {
                switch (topology)
                {
                    case VK_PRIMITIVE_TOPOLOGY_POINT_LIST:
                        return MTLPrimitiveTypePoint;
                    case VK_PRIMITIVE_TOPOLOGY_LINE_LIST:
                        return MTLPrimitiveTypeLine;
                    case VK_PRIMITIVE_TOPOLOGY_LINE_STRIP:
                        return MTLPrimitiveTypeLineStrip;
                    case VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP:
                        return MTLPrimitiveTypeTriangleStrip;
                    default:
                        return MTLPrimitiveTypeTriangle;
                }
            }

            // The fixed function state Metal keeps on the encoder rather than in the pipeline (the pipeline's key
            // leaves it out): culling, winding, depth bias and clamp, the depth and stencil tests, the stencil
            // reference and the blend colour. False when nothing would be drawn (both faces culled).
            bool applyEncoderState(const PipelineState& fixed, const Accumulated& state)
            {
                if (fixed.mCullMode == VK_CULL_MODE_FRONT_AND_BACK)
                    return false;
                EncoderState wanted;
                wanted.mCull = fixed.mCullMode == VK_CULL_MODE_FRONT_BIT ? MTLCullModeFront
                    : fixed.mCullMode == VK_CULL_MODE_BACK_BIT           ? MTLCullModeBack
                                                                         : MTLCullModeNone;
                wanted.mWinding = fixed.mFrontFace == VK_FRONT_FACE_COUNTER_CLOCKWISE ? MTLWindingCounterClockwise
                                                                                      : MTLWindingClockwise;
                if (fixed.mDepthBias)
                {
                    wanted.mBiasConstant = fixed.mBiasConstant;
                    wanted.mBiasSlope = fixed.mBiasSlope;
                }
                wanted.mClamp = fixed.mDepthClamp && mContext.device().mDepthClamp ? MTLDepthClipModeClamp
                                                                                   : MTLDepthClipModeClip;
                wanted.mDepthState = depthStencilState(fixed);
                wanted.mStencilReference = fixed.mStencil ? state.mStencilReference : 0;
                if (fixed.mBlend)
                    std::copy(state.mBlendConstants, state.mBlendConstants + 4, wanted.mBlendColor);
                const bool known = mEncoderStateKnown;
                EncoderState& was = mEncoderState;
                if (!known || was.mCull != wanted.mCull)
                    [mEncoder setCullMode:wanted.mCull];
                if (!known || was.mWinding != wanted.mWinding)
                    [mEncoder setFrontFacingWinding:wanted.mWinding];
                if (!known || was.mBiasConstant != wanted.mBiasConstant || was.mBiasSlope != wanted.mBiasSlope)
                    [mEncoder setDepthBias:wanted.mBiasConstant slopeScale:wanted.mBiasSlope clamp:0.f];
                if (!known || was.mClamp != wanted.mClamp)
                    [mEncoder setDepthClipMode:wanted.mClamp];
                if (!known || was.mDepthState != wanted.mDepthState)
                    [mEncoder setDepthStencilState:wanted.mDepthState];
                if (!known || was.mStencilReference != wanted.mStencilReference)
                    [mEncoder setStencilReferenceValue:wanted.mStencilReference];
                if (!known || !std::equal(wanted.mBlendColor, wanted.mBlendColor + 4, was.mBlendColor))
                    [mEncoder setBlendColorRed:wanted.mBlendColor[0]
                                         green:wanted.mBlendColor[1]
                                          blue:wanted.mBlendColor[2]
                                         alpha:wanted.mBlendColor[3]];
                mEncoderState = wanted;
                mEncoderStateKnown = true;
                return true;
            }

            id<MTLDepthStencilState> depthStencilState(const PipelineState& fixed)
            {
                std::array<uint32_t, 9> key{};
                if (fixed.mDepthTest)
                    key = { 1, fixed.mDepthWrite, fixed.mDepthCompare, 0, 0, 0, 0, 0, 0 };
                if (fixed.mStencil)
                {
                    key[3] = 1;
                    key[4] = fixed.mStencilCompare;
                    key[5] = fixed.mStencilFail;
                    key[6] = fixed.mStencilDepthFail;
                    key[7] = fixed.mStencilPass;
                    key[8] = (fixed.mStencilReadMask & 0xFF) | ((fixed.mStencilWriteMask & 0xFF) << 8);
                }
                __strong id<MTLDepthStencilState>& made = mDepthStates[key];
                if (made != nil)
                    return made;
                MTLDepthStencilDescriptor* descriptor = [MTLDepthStencilDescriptor new];
                // (OpenGL writes no depth with its test off)
                descriptor.depthCompareFunction = key[0] ? mtlCompare(key[2]) : MTLCompareFunctionAlways;
                descriptor.depthWriteEnabled = key[0] && key[1];
                if (key[3])
                {
                    MTLStencilDescriptor* stencil = [MTLStencilDescriptor new];
                    stencil.stencilCompareFunction = mtlCompare(key[4]);
                    stencil.stencilFailureOperation = mtlStencilOp(key[5]);
                    stencil.depthFailureOperation = mtlStencilOp(key[6]);
                    stencil.depthStencilPassOperation = mtlStencilOp(key[7]);
                    stencil.readMask = key[8] & 0xFF;
                    stencil.writeMask = (key[8] >> 8) & 0xFF;
                    descriptor.frontFaceStencil = stencil;
                    descriptor.backFaceStencil = stencil;
                }
                made = [mContext.mtl() newDepthStencilStateWithDescriptor:descriptor];
                return made;
            }

            struct EncoderState
            {
                MTLCullMode mCull = MTLCullModeNone;
                MTLWinding mWinding = MTLWindingClockwise;
                float mBiasConstant = 0.f, mBiasSlope = 0.f;
                MTLDepthClipMode mClamp = MTLDepthClipModeClip;
                id<MTLDepthStencilState> mDepthState = nil;
                uint32_t mStencilReference = 0;
                float mBlendColor[4]{};
            };
            EncoderState mEncoderState;
            bool mEncoderStateKnown = false;
            std::map<std::array<uint32_t, 9>, id<MTLDepthStencilState>> mDepthStates;
            std::set<int> mFormatsLogged;

            void bindVertexBuffers(const std::vector<id<MTLBuffer>>& buffers, const std::vector<NSUInteger>& offsets)
            {
                for (size_t i = 0; i < buffers.size() && i < sMaxVertexBuffers; ++i)
                    bindBuffer(0, sVertexBufferBase + static_cast<uint32_t>(i), buffers[i], offsets[i]);
            }

            void drawGeometry(const osg::Geometry& geometry, Accumulated& state, const osg::RefMatrix* modelView,
                const osg::RefMatrix* projection, bool noDepthWrite = false)
            {
                derive(state, mFrameCounter);
                // the statistics' geometry has no program of its own (OSG gives it one only for some shader hints):
                // OSG's own for it, its vertices' colours
                if (mDrawingStatistics)
                {
                    if (state.mProgram == nullptr)
                        state.mProgram = statisticsProgram();
                    // blending without a function of its own: under OpenGL the one the GUI left set (alpha), as
                    // its translucent backgrounds are drawn there
                    if (state.mFixed.mBlend && state.attribute(osg::StateAttribute::BLENDFUNC) == nullptr)
                    {
                        state.mFixed.mSrcColor = VK_BLEND_FACTOR_SRC_ALPHA;
                        state.mFixed.mDstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                        state.mFixed.mSrcAlpha = VK_BLEND_FACTOR_ONE;
                        state.mFixed.mDstAlpha = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                    }
                }
                if (state.mProgram == nullptr || state.mProgram->getNumShaders() == 0)
                {
                    ++mSkipped;
                    return;
                }
                Variant* variant = variantOf(state);
                if (variant == nullptr || !variant->mOk)
                {
                    ++mSkipped;
                    return;
                }
                if (mEncoder == nil)
                    return;
                GeometryEntry* entry = geometryEntry(geometry, *variant, state.mProgram->getAttribBindingList());
                if (entry == nullptr || entry->mPrimitives.empty())
                    return;

                // the pipelines for the state and pass (one for each primitive set's topology)
                PipelineState fixed = pipelineState(state, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                if (noDepthWrite)
                    fixed.mDepthWrite = 0;
                // Frame generation tells the first person model's pixels (they move with the camera) by the depth they
                // have after its depth clear. Its parts that write none (a bow's blended string, effects drawn without
                // the test) would move with the world behind them: a second string beside the real one in the generated
                // frames, and gaps in it. With frame generation on, they write depth too (into the model's own depth,
                // after its opaque parts).
                else if (mInFirstPersonBin && !fixed.mDepthWrite && frameGenWanted())
                {
                    fixed.mDepthWrite = 1;
                    fixed.mDepthTest = 1;
                }
                GeometryEntry::Pipelines* pipelines = nullptr;
                for (auto& candidate : entry->mPipelines)
                    if (candidate.mUsed && std::memcmp(&candidate.mFixed, &fixed, sizeof(fixed)) == 0)
                        pipelines = &candidate;
                if (pipelines == nullptr)
                {
                    pipelines = &entry->mPipelines[entry->mNextPipelines++ % entry->mPipelines.size()];
                    pipelines->mUsed = true;
                    pipelines->mFixed = fixed;
                    pipelines->mPerPrimitive.assign(entry->mPrimitives.size(), nil);
                    pipelines->mWaiting = true;
                    pipelines->mGeneration = ~0ull;
                }
                // asked for again while one is being made, once the programs have put new ones in
                if (pipelines->mWaiting && pipelines->mGeneration != mPrograms.pipelineGeneration())
                {
                    pipelines->mGeneration = mPrograms.pipelineGeneration();
                    pipelines->mWaiting = false;
                    for (size_t i = 0; i < entry->mPrimitives.size(); ++i)
                    {
                        if (pipelines->mPerPrimitive[i] != nil)
                            continue;
                        PipelineState withTopology = fixed;
                        withTopology.mTopology = entry->mPrimitives[i].mTopology;
                        pipelines->mPerPrimitive[i]
                            = mPrograms.pipeline(*variant, withTopology, entry->mLayout, mWaitForPrograms);
                        if (pipelines->mPerPrimitive[i] == nil)
                            pipelines->mWaiting = true;
                    }
                    mPipelineCount = mPrograms.pipelineCount();
                }

                DrawTemplate* draw = templateFor(state, *variant);
                bool bound = false;
                for (size_t i = 0; i < entry->mPrimitives.size(); ++i)
                {
                    const GeometryEntry::Primitive& primitive = entry->mPrimitives[i];
                    id<MTLRenderPipelineState> pipeline = pipelines->mPerPrimitive[i];
                    if (pipeline == nil)
                        continue;
                    const osg::PrimitiveSet& primitives = *primitive.mSet;
                    const bool indexed = primitive.mIndexBuffer != nil;
                    if (!indexed && primitives.getDrawElements() != nullptr)
                        continue;
                    if (!bound)
                    {
                        if (!applyEncoderState(fixed, state))
                            return;
                        if (!bindUniforms(*draw, modelView, projection, state.mClipModes))
                            return;
                        if (mBoundVertices != entry || mBoundVerticesVersion != entry->mVersion)
                        {
                            bindVertexBuffers(entry->mBuffers, entry->mOffsets);
                            ++mVertexBinds;
                            mBoundVertices = entry;
                            mBoundVerticesVersion = entry->mVersion;
                        }
                        setViewport(state.mMinDepth, state.mMaxDepth);
                        bound = true;
                    }
                    if (pipeline != mBoundPipeline)
                    {
                        [mEncoder setRenderPipelineState:pipeline];
                        mBoundPipeline = pipeline;
                        ++mPipelineBinds;
                    }
                    const MTLPrimitiveType type = primitiveOf(primitive.mTopology);
                    const NSUInteger instances = std::max(1u, static_cast<unsigned>(primitives.getNumInstances()));
                    if (indexed)
                    {
                        const uint32_t count = primitive.mConverted ? primitive.mIndexCount
                                                                    : primitives.getDrawElements()->getNumIndices();
                        [mEncoder drawIndexedPrimitives:type
                                             indexCount:count
                                              indexType:primitive.mIndexType == VK_INDEX_TYPE_UINT32
                                                  ? MTLIndexTypeUInt32
                                                  : MTLIndexTypeUInt16
                                            indexBuffer:primitive.mIndexBuffer
                                      indexBufferOffset:primitive.mIndexOffset
                                          instanceCount:instances];
                        mVertices += static_cast<uint64_t>(count) * instances;
                    }
                    else if (primitives.getType() == osg::PrimitiveSet::DrawArrayLengthsPrimitiveType)
                    {
                        const auto& lengths = static_cast<const osg::DrawArrayLengths&>(primitives);
                        NSUInteger first = static_cast<NSUInteger>(lengths.getFirst());
                        for (GLsizei length : lengths)
                        {
                            if (length > 0)
                                [mEncoder drawPrimitives:type
                                             vertexStart:first
                                             vertexCount:static_cast<NSUInteger>(length)
                                           instanceCount:instances];
                            mVertices += static_cast<uint64_t>(length) * instances;
                            first += static_cast<NSUInteger>(length);
                        }
                    }
                    else if (primitives.getType() == osg::PrimitiveSet::DrawArraysPrimitiveType)
                    {
                        const auto& arrays = static_cast<const osg::DrawArrays&>(primitives);
                        if (arrays.getCount() <= 0)
                            continue;
                        [mEncoder drawPrimitives:type
                                     vertexStart:static_cast<NSUInteger>(arrays.getFirst())
                                     vertexCount:static_cast<NSUInteger>(arrays.getCount())
                                   instanceCount:instances];
                        mVertices += static_cast<uint64_t>(arrays.getCount()) * instances;
                    }
                    else
                        continue;
                    ++mDraws;
                }
            }

            void drawMyGui(const MyGuiDrawable& gui, const osgUtil::RenderLeaf& leaf,
                const std::vector<const osgUtil::RenderBin*>& bins)
            {
                // what OSG's draw of it does: the next of its buffered batch lists
                gui.mReadFrom = (gui.mReadFrom + 1) % 4;
                const std::vector<MyGuiBatch>& batches = gui.mBatchVector[gui.mReadFrom];
                if (batches.empty())
                    return;
                Accumulated* base = step(accumulate(leaf._parent, bins), gui.mStateSet.get());
                if (mEncoder == nil)
                    return;
                // the GUI's programs and pipelines made at once, as OpenGL does: left out while a thread makes them,
                // a screen of it is missing for a frame (the window's clear colour, OSG's purple, between the
                // loading screens at the start)
                const bool waitedBefore = mWaitForPrograms;
                mWaitForPrograms = true;
                struct RestoreWait
                {
                    bool& mWait;
                    bool mBefore;
                    ~RestoreWait() { mWait = mBefore; }
                } restoreWait{ mWaitForPrograms, waitedBefore };
                for (const MyGuiBatch& batch : batches)
                {
                    if (!batch.mArray || batch.mVertexCount == 0)
                        continue;
                    Accumulated* state = step(base, batch.mStateSet.get());
                    // the batch's texture on unit 0
                    const osg::Texture2D* texture = batch.mTexture ? batch.mTexture.get() : gui.mDummyTexture.get();
                    Accumulated* textured = step(state, nullptr, texture);
                    derive(*textured, mFrameCounter);
                    if (textured->mProgram == nullptr)
                        continue;
                    Variant* variant = variantOf(*textured);
                    if (variant == nullptr || !variant->mOk)
                        continue;
                    const size_t bytes = batch.mVertexCount * 24;
                    if (batch.mArray->getTotalDataSize() < bytes)
                        continue;
                    Span span = mResources.stream(bytes, 16);
                    if (span.mData == nullptr)
                        return;
                    std::memcpy(span.mData, batch.mArray->getDataPointer(), bytes);

                    VertexLayout layout;
                    std::vector<id<MTLBuffer>> buffers;
                    std::vector<NSUInteger> offsets;
                    for (const VertexInput& input : variant->mInputs)
                    {
                        const uint32_t binding = static_cast<uint32_t>(layout.mBindings.size());
                        VkFormat format = VK_FORMAT_R32G32B32A32_SFLOAT;
                        uint32_t offset = 0, stride = 24;
                        if (input.mName == "osg_Vertex")
                            format = VK_FORMAT_R32G32B32_SFLOAT;
                        else if (input.mName == "osg_Color")
                            format = VK_FORMAT_R8G8B8A8_UNORM, offset = 12;
                        else if (input.mName == "osg_MultiTexCoord0")
                            format = VK_FORMAT_R32G32_SFLOAT, offset = 16;
                        else
                            stride = 0; // reads the first vertex's position: nothing the GUI shader uses
                        layout.mBindings.push_back({ binding, stride, VK_VERTEX_INPUT_RATE_VERTEX });
                        layout.mAttributes.push_back({ input.mLocation, binding, format, offset });
                        buffers.push_back(span.mBuffer);
                        offsets.push_back(span.mOffset);
                    }
                    const PipelineState fixed = pipelineState(*textured, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                    id<MTLRenderPipelineState> pipeline = mPrograms.pipeline(*variant, fixed, layout, true);
                    if (pipeline == nil)
                        continue;
                    DrawTemplate* draw = templateFor(*textured, *variant);
                    if (!applyEncoderState(fixed, *textured))
                        continue;
                    if (!bindUniforms(*draw, leaf._modelview.get(), leaf._projection.get(), textured->mClipModes))
                        return;
                    [mEncoder setRenderPipelineState:pipeline];
                    mBoundPipeline = pipeline;
                    bindVertexBuffers(buffers, offsets);
                    mBoundVertices = nullptr;
                    setViewport(textured->mMinDepth, textured->mMaxDepth);
                    [mEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                                 vertexStart:0
                                 vertexCount:static_cast<NSUInteger>(batch.mVertexCount)];
                    ++mDraws;
                }
            }

            // osgText::Text as its draw draws it: its arrays, each glyph texture's quads with that texture on unit 0.
            // The glyph textures are loaded glyph by glyph into OpenGL (subloads); their image (GlyphTexture's own,
            // which each glyph is copied into) is drawn through a texture of the translator's.
            void drawText(const osgText::Text& text, const osgUtil::RenderLeaf& leaf,
                const std::vector<const osgUtil::RenderBin*>& bins)
            {
                const auto& coords = TextArrays::coords(text);
                if (!coords || coords->empty())
                    return;
                TextEntry& entry = mTexts[&text];
                entry.mText = &text;
                entry.mLastUse = mFrameNumber;
                Accumulated* base = accumulate(leaf._parent, bins);
                // as its draw: its place (the matrix computePositions keeps: position, rotation, offset) before the
                // modelview (text sized in screen coordinates, which needs the matrix of each view, is not done)
                osg::ref_ptr<osg::RefMatrix> modelView = new osg::RefMatrix(text.getMatrix());
                if (leaf._modelview.valid())
                    modelView->postMult(*leaf._modelview);
                for (const auto& [glyphTexture, quads] : text.getTextureGlyphQuadMap())
                {
                    if (!glyphTexture || !quads._primitives || quads._primitives->getNumIndices() == 0)
                        continue;
                    GlyphProxy& proxy = mGlyphTextures[glyphTexture.get()];
                    if (!proxy.mTexture)
                    {
                        proxy.mSource = const_cast<osgText::GlyphTexture&>(*glyphTexture).createImage();
                        proxy.mTexture = new osg::Texture2D;
                        proxy.mTexture->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
                        proxy.mTexture->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);
                        proxy.mTexture->setWrap(osg::Texture::WRAP_S, osg::Texture::CLAMP_TO_EDGE);
                        proxy.mTexture->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);
                        proxy.mTexture->setResizeNonPowerOfTwoHint(false);
                    }
                    // the glyphs as the shader reads them compiled as desktop GLSL (its ALPHA is .r and SDF .g, not
                    // OpenGL ES's .a and .r): the OpenGL ES image (alpha, or luminance the distance and alpha the
                    // glyph) copied with its channels so, again when glyphs are added
                    if (proxy.mSource && proxy.mSource->getModifiedCount() != proxy.mModified)
                    {
                        proxy.mModified = proxy.mSource->getModifiedCount();
                        const osg::Image& source = *proxy.mSource;
                        const bool sdf = source.getPixelFormat() == GL_LUMINANCE_ALPHA;
                        osg::ref_ptr<osg::Image> image = new osg::Image;
                        image->allocateImage(source.s(), source.t(), 1, sdf ? 0x8227 /* GL_RG */ : 0x1903 /* GL_RED */,
                            GL_UNSIGNED_BYTE);
                        const unsigned char* from = source.data();
                        unsigned char* to = image->data();
                        const size_t pixels = static_cast<size_t>(source.s()) * source.t();
                        if (sdf)
                            for (size_t i = 0; i < pixels; ++i)
                            {
                                to[i * 2] = from[i * 2 + 1];
                                to[i * 2 + 1] = from[i * 2];
                            }
                        else
                            std::memcpy(to, from, pixels);
                        proxy.mTexture->setImage(image);
                    }
                    osg::ref_ptr<osg::Geometry>& geometry = entry.mGeometries[glyphTexture.get()];
                    if (!geometry)
                    {
                        geometry = new osg::Geometry;
                        geometry->setUseVertexBufferObjects(true);
                    }
                    if (geometry->getVertexArray() != coords.get())
                        geometry->setVertexArray(coords.get());
                    // its colour: one (SOLID, given to OpenGL as the current colour), else a gradient's per vertex
                    const auto& colors = TextArrays::colors(text);
                    if (text.getColorGradientMode() == osgText::Text::SOLID || !colors)
                    {
                        auto* color = dynamic_cast<osg::Vec4Array*>(geometry->getColorArray());
                        if (color == nullptr || color->getBinding() != osg::Array::BIND_OVERALL)
                        {
                            color = new osg::Vec4Array(1);
                            geometry->setColorArray(color, osg::Array::BIND_OVERALL);
                        }
                        if ((*color)[0] != text.getColor())
                        {
                            (*color)[0] = text.getColor();
                            color->dirty();
                        }
                    }
                    else if (geometry->getColorArray() != colors.get())
                        geometry->setColorArray(colors.get(), osg::Array::BIND_PER_VERTEX);
                    const auto& texCoords = TextArrays::texCoords(text);
                    if (texCoords && geometry->getTexCoordArray(0) != texCoords.get())
                        geometry->setTexCoordArray(0, texCoords.get(), osg::Array::BIND_PER_VERTEX);
                    if (geometry->getNumPrimitiveSets() != 1 || geometry->getPrimitiveSet(0) != quads._primitives.get())
                    {
                        geometry->removePrimitiveSet(0, geometry->getNumPrimitiveSets());
                        geometry->addPrimitiveSet(quads._primitives.get());
                    }
                    Accumulated* state = step(base, nullptr, proxy.mTexture.get());
                    setChain(leaf._parent, &bins, state);
                    drawGeometry(*geometry, *state, modelView.get(), leaf._projection.get());
                    setChain(nullptr, nullptr, nullptr);
                }
            }

            struct TextEntry
            {
                osg::observer_ptr<const osgText::Text> mText;
                std::map<const osgText::GlyphTexture*, osg::ref_ptr<osg::Geometry>> mGeometries;
                unsigned mLastUse = 0;
            };
            std::map<const osgText::Text*, TextEntry> mTexts;
            struct GlyphProxy
            {
                osg::ref_ptr<osg::Image> mSource; // the glyph texture's own image
                unsigned mModified = ~0u;
                osg::ref_ptr<osg::Texture2D> mTexture;
            };
            std::map<const osgText::GlyphTexture*, GlyphProxy> mGlyphTextures;

            // osgViewer's statistics (F3, OpenMW's profiler): its camera (drawn by OSG's own renderer into the OpenGL
            // ES pbuffer, whose draw callbacks keep its text and graphs up to date) culled again and drawn over the
            // window, the last thing in it
            void drawStatistics(osgUtil::SceneView& sceneView)
            {
                osg::Camera* mainCamera = sceneView.getCamera();
                auto* view = mainCamera != nullptr ? dynamic_cast<osgViewer::View*>(mainCamera->getView()) : nullptr;
                if (view == nullptr)
                    return;
                for (const auto& handler : view->getEventHandlers())
                {
                    auto* stats = dynamic_cast<osgViewer::StatsHandler*>(handler.get());
                    if (stats == nullptr || StatsPage::of(*stats) == 0)
                        continue;
                    osg::Camera* camera = stats->getCamera();
                    if (camera == nullptr || camera->getNodeMask() == 0 || camera->getViewport() == nullptr)
                        continue;
                    if (!mStatsView)
                    {
                        mStatsView = new osgUtil::SceneView;
                        mStatsView->setDefaults(osgUtil::SceneView::NO_SCENEVIEW_LIGHT);
                        mStatsView->setComputeNearFarMode(osg::CullSettings::DO_NOT_COMPUTE_NEAR_FAR);
                    }
                    mStatsView->setCamera(camera, false);
                    mStatsView->setState(mState);
                    mStatsView->setFrameStamp(const_cast<osg::FrameStamp*>(sceneView.getFrameStamp()));
                    mStatsView->inheritCullSettings(*camera);
                    mStatsView->cull();
                    mDrawingStatistics = true;
                    if (osgUtil::RenderStage* stage = mStatsView->getRenderStage())
                        drawStage(*stage);
                    mDrawingStatistics = false;
                }
                // the text not drawn for a while
                if (mFrameNumber % 300 == 0)
                    for (auto it = mTexts.begin(); it != mTexts.end();)
                    {
                        osg::ref_ptr<const osgText::Text> text;
                        if (!it->second.mText.lock(text) || mFrameNumber - it->second.mLastUse > 600)
                            it = mTexts.erase(it);
                        else
                            ++it;
                    }
            }
            osg::ref_ptr<osgUtil::SceneView> mStatsView;
            bool mDrawingStatistics = false;
            osg::ref_ptr<osg::Program> mStatisticsProgram;

            const osg::Program* statisticsProgram()
            {
                if (!mStatisticsProgram)
                {
                    mStatisticsProgram = new osg::Program;
                    mStatisticsProgram->setName("a3 statistics");
                    mStatisticsProgram->addShader(new osg::Shader(osg::Shader::VERTEX,
                        "#version 300 es\n"
                        "precision highp float;\n"
                        "in vec4 osg_Vertex;\n"
                        "in vec4 osg_Color;\n"
                        "uniform mat4 osg_ModelViewProjectionMatrix;\n"
                        "out vec4 vertexColor;\n"
                        "void main()\n"
                        "{\n"
                        "    gl_Position = osg_ModelViewProjectionMatrix * osg_Vertex;\n"
                        "    vertexColor = osg_Color;\n"
                        "}\n"));
                    mStatisticsProgram->addShader(new osg::Shader(osg::Shader::FRAGMENT,
                        "#version 300 es\n"
                        "precision highp float;\n"
                        "in vec4 vertexColor;\n"
                        "out vec4 color;\n"
                        "void main()\n"
                        "{\n"
                        "    color = vertexColor;\n"
                        "}\n"));
                }
                return mStatisticsProgram.get();
            }

            // osgParticle's quads, made as its draw makes them (ParticleSystem::drawImplementation, the path without
            // vertex arrays), into a geometry kept for the system; drawn without depth writes as it is
            void drawParticles(const osgParticle::ParticleSystem& system, const osgUtil::RenderLeaf& leaf,
                const std::vector<const osgUtil::RenderBin*>& bins)
            {
                ParticleAccess::drawn(system, mFrameNumber);
                osg::ref_ptr<osg::Geometry>& geometry = mParticles[&system].mGeometry;
                mParticles[&system].mSystem = &system;
                mParticles[&system].mLastUse = mFrameNumber;
                if (!geometry)
                {
                    geometry = new osg::Geometry;
                    geometry->setVertexArray(new osg::Vec3Array);
                    geometry->setColorArray(new osg::Vec4Array, osg::Array::BIND_PER_VERTEX);
                    geometry->setTexCoordArray(0, new osg::Vec2Array, osg::Array::BIND_PER_VERTEX);
                    osg::ref_ptr<osg::Vec3Array> normal = new osg::Vec3Array(1);
                    (*normal)[0] = osg::Vec3(0.3f, 0.3f, 0.3f);
                    geometry->setNormalArray(normal, osg::Array::BIND_OVERALL);
                    geometry->addPrimitiveSet(new osg::DrawArrays(GL_TRIANGLES, 0, 0));
                }
                auto& vertices = static_cast<osg::Vec3Array&>(*geometry->getVertexArray());
                auto& colors = static_cast<osg::Vec4Array&>(*geometry->getColorArray());
                auto& texcoords = static_cast<osg::Vec2Array&>(*geometry->getTexCoordArray(0));
                vertices.clear();
                colors.clear();
                texcoords.clear();
                {
                    osgParticle::ParticleSystem::ScopedReadLock lock(*system.getReadWriteMutex());
                    const osg::Matrix modelView = leaf._modelview.valid() ? osg::Matrix(*leaf._modelview) : osg::Matrix();
                    const int detail = std::max(system.getLevelOfDetail(), 1);
                    const float scale = std::sqrt(static_cast<float>(detail));
                    osg::Vec3 xAxis = system.getAlignVectorX();
                    osg::Vec3 yAxis = system.getAlignVectorY();
                    osg::Vec3 scaledX = xAxis, scaledY = yAxis;
                    if (system.getParticleAlignment() == osgParticle::ParticleSystem::BILLBOARD)
                    {
                        xAxis = osg::Matrix::transform3x3(modelView, xAxis);
                        yAxis = osg::Matrix::transform3x3(modelView, yAxis);
                        const bool local = system.getParticleScaleReferenceFrame()
                            == osgParticle::ParticleSystem::LOCAL_COORDINATES;
                        const float xScale = local ? 1.f / std::sqrt(xAxis.length2()) : 1.f / xAxis.length2();
                        const float yScale = local ? 1.f / std::sqrt(yAxis.length2()) : 1.f / yAxis.length2();
                        scaledX *= xScale;
                        scaledY *= yScale;
                        xAxis *= xScale;
                        yAxis *= yScale;
                    }
                    const bool sorted = system.getSortMode() != osgParticle::ParticleSystem::NO_SORT
                        && system.getVisibilityDistance() > 0.0;
                    for (int i = 0; i < system.numParticles(); i += detail)
                    {
                        const osgParticle::Particle* particle = system.getParticle(i);
                        if (!particle->isAlive())
                            continue;
                        if (sorted
                            && (particle->getDepth() < 0.0 || particle->getDepth() > system.getVisibilityDistance()))
                            continue;
                        const osgParticle::Particle::Shape shape = particle->getShape();
                        if (shape == osgParticle::Particle::POINT || shape == osgParticle::Particle::LINE)
                            continue;
                        osg::Vec3 x = xAxis, y = yAxis;
                        const osg::Vec3& angle = particle->getAngle();
                        if (angle.x() != 0.f || angle.y() != 0.f || angle.z() != 0.f)
                        {
                            osg::Matrix rotation;
                            rotation.makeRotate(angle.x(), osg::Vec3(1, 0, 0), angle.y(), osg::Vec3(0, 1, 0),
                                angle.z(), osg::Vec3(0, 0, 1));
                            x = osg::Matrix::transform3x3(scaledX, rotation);
                            y = osg::Matrix::transform3x3(scaledY, rotation);
                            if (system.getParticleAlignment() == osgParticle::ParticleSystem::BILLBOARD)
                            {
                                x = osg::Matrix::transform3x3(modelView, x);
                                y = osg::Matrix::transform3x3(modelView, y);
                            }
                        }
                        osg::Vec4 color = particle->getCurrentColor();
                        color.a() *= particle->getCurrentAlpha();
                        const float size = particle->getCurrentSize();
                        const osg::Vec3& position = particle->getPosition();
                        const float s = particle->getSTexCoord(), t = particle->getTTexCoord();
                        const float sTile = particle->getSTexTile(), tTile = particle->getTTexTile();
                        const osg::Vec3 p1 = x * size * scale, p2 = y * size * scale;
                        const osg::Vec3 c0 = position - p1 - p2, c1 = position + p1 - p2, c2 = position + p1 + p2,
                                        c3 = position - p1 + p2;
                        const osg::Vec2 t0(s, t), t1(s + sTile, t), t2(s + sTile, t + tTile), t3(s, t + tTile);
                        for (const auto& [corner, uv] : { std::make_pair(c0, t0), std::make_pair(c1, t1),
                                 std::make_pair(c2, t2), std::make_pair(c2, t2), std::make_pair(c3, t3),
                                 std::make_pair(c0, t0) })
                        {
                            vertices.push_back(corner);
                            texcoords.push_back(uv);
                            colors.push_back(color);
                        }
                    }
                }
                if (vertices.empty())
                    return;
                vertices.dirty();
                colors.dirty();
                texcoords.dirty();
                static_cast<osg::DrawArrays&>(*geometry->getPrimitiveSet(0)).setCount(static_cast<GLsizei>(vertices.size()));
                Accumulated* state = accumulate(leaf._parent, bins);
                setChain(leaf._parent, &bins, state);
                drawGeometry(*geometry, *state, leaf._modelview.get(), leaf._projection.get(), true);
                setChain(nullptr, nullptr, nullptr);
            }

            // The terrain's composite maps (CompositeMapRenderer::compile): quads in clip space drawn into a texture,
            // pass after pass. Done before the frame's passes, from the queues the renderer had when last culled.
            void renderCompositeMaps()
            {
                osg::ref_ptr<const osg::Drawable> alive;
                if (!mCompositeRenderer.lock(alive))
                    return;
                const auto& queues = static_cast<const CompositeMapQueues&>(*alive);
                std::vector<std::shared_ptr<Terrain::CompositeMap>> maps;
                {
                    std::lock_guard<std::mutex> lock(queues.mMutex);
                    for (const auto& map : queues.mImmediateCompileSet)
                        maps.push_back(map);
                    queues.mImmediateCompileSet.clear();
                    for (auto it = queues.mCompileSet.begin(); it != queues.mCompileSet.end() && maps.size() < 24;)
                    {
                        maps.push_back(*it);
                        it = queues.mCompileSet.erase(it);
                    }
                }
                if (maps.empty())
                    return;
                // drawn once and read from then on: their programs are compiled first, not left out meanwhile
                mWaitForPrograms = true;
                const osg::ref_ptr<osg::RefMatrix> identity = new osg::RefMatrix;
                for (const auto& map : maps)
                {
                    if (!map->mTexture || map->mTexture->referenceCount() <= 1)
                    {
                        map->mCompiled = map->mDrawables.size();
                        map->mDrawables.clear();
                        continue;
                    }
                    Image* image = mResources.renderTarget(*map->mTexture);
                    if (image == nullptr)
                        continue;
                    Target target;
                    target.mWindow = false;
                    target.mColor = image;
                    target.mWidth = image->mWidth;
                    target.mHeight = image->mHeight;
                    target.mColorFormat = image->mFormat;
                    if (!beginTargetPass(target, { { 0, 0 }, { image->mWidth, image->mHeight } }, nullptr, false, 1.f, 0,
                            @"composite map"))
                        continue;
                    for (size_t i = map->mCompiled; i < map->mDrawables.size(); ++i)
                    {
                        const osg::Geometry* geometry
                            = map->mDrawables[i] ? map->mDrawables[i]->asGeometry() : nullptr;
                        if (geometry != nullptr)
                        {
                            drawGeometry(
                                *geometry, *step(&mEmpty, geometry->getStateSet()), identity.get(), identity.get());
                        }
                    }
                    mContext.endEncoder();
                    mEncoder = nil;
                    map->mCompiled = map->mDrawables.size();
                    map->mDrawables.clear();
                }
                mWaitForPrograms = false;
            }

            // The water's ripples, as RipplesSurface::drawImplementation does with its fragment shaders: new ripples
            // blotted into the second texture from the first, then a step of the wave simulation back into the first
            // (which the water reads). The state of this frame was set by the engine's cull.
            void renderRipples()
            {
                osg::ref_ptr<osg::Geometry> alive;
                if (!mRipples.lock(alive))
                    return;
                const auto& ripples = static_cast<const RipplesSurfaceMirror&>(*alive);
                if (ripples.mResourceSystem == nullptr || !ripples.mTextures[0] || !ripples.mTextures[1])
                    return;
                const RipplesSurfaceMirror::State& frame = ripples.mState[mFrameNumber % 2];
                Image* images[2] = { mResources.renderTarget(*ripples.mTextures[0]),
                    mResources.renderTarget(*ripples.mTextures[1]) };
                if (images[0] == nullptr || images[1] == nullptr || !frame.mStateset)
                    return;
                // new images hold nothing yet: no waves
                if (frame.mClear || images[0] != mRippleImages[0] || images[1] != mRippleImages[1])
                {
                    clearColor(*images[0], {});
                    clearColor(*images[1], {});
                    mRippleImages[0] = images[0];
                    mRippleImages[1] = images[1];
                }
                if (frame.mPaused)
                    return;
                if (!mRipplePasses[0])
                {
                    if (mRipplesFailed)
                        return;
                    Shader::ShaderManager& shaders = ripples.mResourceSystem->getSceneManager()->getShaderManager();
                    const Shader::ShaderManager::DefineMap defines = { { "rippleMapSize", "1024.0" } };
                    osg::ref_ptr<osg::Shader> vertex = shaders.getShader("fullscreen_tri.vert", {}, osg::Shader::VERTEX);
                    osg::ref_ptr<osg::Shader> blobber
                        = shaders.getShader("ripples_blobber.frag", defines, osg::Shader::FRAGMENT);
                    osg::ref_ptr<osg::Shader> simulate
                        = shaders.getShader("ripples_simulate.frag", defines, osg::Shader::FRAGMENT);
                    if (!vertex || !blobber || !simulate)
                    {
                        Log(Debug::Error) << "Metal: the ripple shaders are missing";
                        mRipplesFailed = true;
                        return;
                    }
                    mRippleTime = new osg::Uniform("osg_SimulationTime", 0.f);
                    const osg::ref_ptr<osg::Shader> shaderPair[2] = { blobber, simulate };
                    for (int i = 0; i < 2; ++i)
                    {
                        osg::ref_ptr<osg::StateSet> set = new osg::StateSet;
                        set->setAttribute(shaders.getProgram(vertex, shaderPair[i]));
                        // each pass reads the texture the other one writes
                        set->setTextureAttribute(0, ripples.mTextures[i]);
                        set->addUniform(new osg::Uniform("imageIn", 0));
                        set->addUniform(mRippleTime);
                        set->setMode(GL_DEPTH_TEST, osg::StateAttribute::OFF);
                        set->setMode(GL_BLEND, osg::StateAttribute::OFF);
                        set->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                        mRipplePasses[i] = set;
                    }
                }
                if (const osg::FrameStamp* stamp = mState ? mState->getFrameStamp() : nullptr)
                    mRippleTime->set(static_cast<float>(stamp->getSimulationTime()));

                const osg::ref_ptr<osg::RefMatrix> identity = new osg::RefMatrix;
                Accumulated* base = step(&mEmpty, frame.mStateset.get());
                // blot: texture 0 into 1; simulate: texture 1 into 0
                for (int pass = 0; pass < 2; ++pass)
                {
                    Image* image = images[pass == 0 ? 1 : 0];
                    Target target;
                    target.mWindow = false;
                    target.mColor = image;
                    target.mWidth = image->mWidth;
                    target.mHeight = image->mHeight;
                    target.mColorFormat = image->mFormat;
                    // (every texel is written)
                    if (!beginTargetPass(target, { { 0, 0 }, { image->mWidth, image->mHeight } }, nullptr, false, 1.f, 0,
                            pass == 0 ? @"ripples (blot)" : @"ripples (simulate)"))
                        return;
                    drawGeometry(*alive, *step(base, mRipplePasses[pass].get()), identity.get(), identity.get());
                    mContext.endEncoder();
                    mEncoder = nil;
                }
            }

            void clearColor(Image& image, const VkClearColorValue& color)
            {
                MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
                pass.colorAttachments[0].texture = image.mTexture;
                pass.colorAttachments[0].loadAction = MTLLoadActionClear;
                pass.colorAttachments[0].storeAction = MTLStoreActionStore;
                pass.colorAttachments[0].clearColor
                    = MTLClearColorMake(color.float32[0], color.float32[1], color.float32[2], color.float32[3]);
                mContext.beginPass(pass, @"clear");
                mContext.endEncoder();
                mEncoder = nil;
            }

            // ---- frame generation ----

            // After a rendered frame: what the generated frames are made from (the window as it is now, with the GUI;
            // the scene's colour and depths stay as they are until the frame after next draws into them again)
            void captureForFrameGen()
            {
                mGen.mValid = false;
                if (!frameGenWanted())
                    return;
                const FrameGenClock::time_point now = FrameGenClock::now();
                // the pattern of rendered and generated frames, from how fast the rendered ones come against the display's
                // refresh (120 Hz on ProMotion: a 60 fps game shows 120)
                const double period = mContext.displayPeriodMs();
                mGenLock.frame(now, period, std::min(mGenMaxFrames + 1, FrameGenLock::sStepCount - 1));
                if (mGenLock.generated() == 0 || mSceneColor == nullptr || mSceneColor->mDepthAspect)
                    return;
                FrameGenView view;
                if (!frameGenFindView(mFrameNumber, view) || !view.depth || !view.opaqueDepth)
                    return;
                // the depths the scene was drawn with this frame (the engine's textures of the frame number's parity can
                // be the other pair: the first person model's depth from a frame before, its colour moved with the world)
                Image* depth = mSceneDepth != nullptr ? mSceneDepth : mResources.renderTarget(*view.depth);
                Image* opaque = mSceneOpaqueDepth != nullptr ? mSceneOpaqueDepth : mResources.renderTarget(*view.opaqueDepth);
                if (depth == nullptr || opaque == nullptr || !depth->mDepthAspect || !opaque->mDepthAspect)
                    return;
                const uint32_t width = mContext.windowWidth(), height = mContext.windowHeight();
                Image* final = mResources.renderTarget(&mGen, width, height, 0x8058 /* GL_RGBA8 */);
                if (final == nullptr || !mContext.blitWindow(final->mTexture))
                    return;
                mGen.mValid = true;
                mGen.mView = view;
                // the scene's colour: the window has it as it is (drawCanvas) with the GUI over it, or the post
                // processing chain's output before the GUI
                mGen.mScene = mPostColor != nullptr ? mPostColor : mSceneColor;
                mGen.mFirstPerson = mFirstPersonCleared;
                mGen.mDepth = depth;
                mGen.mOpaqueDepth = opaque;
                mGen.mFinal = final;
                mGen.mHavePrev = frameGenFindView(mFrameNumber - 1, mGen.mPrev);
            }

            // Before the next rendered frame is drawn (its camera known by now): the generated frames, each shown
            // from a camera part way to the new one, presented ahead of it (each takes a refresh of its own: the
            // layer presents in order, one a refresh)
            void generateFrames(osgUtil::SceneView& sceneView)
            {
                if (!mGen.mValid)
                    return;
                mGen.mValid = false;
                const FrameGenView& cur = mGen.mView;
                double fovy = 0, aspect = 0, zNear = 0, zFar = 0;
                if (!cur.projection.getPerspective(fovy, aspect, zNear, zFar) || zNear <= 0.0 || zFar <= zNear)
                    return;
                FrameGenView next;
                const unsigned nextFrame
                    = sceneView.getFrameStamp() ? sceneView.getFrameStamp()->getFrameNumber() : cur.frame;
                const bool known = frameGenFindView(nextFrame, next) && next.frame > cur.frame;
                if (!known && !mGen.mHavePrev)
                    return;
                // a cut (through a seamless door the camera is in another cell's space from one frame to the next):
                // there is nothing between the two views
                {
                    const osg::Vec3d here = osg::Matrixd::inverse(cur.view).getTrans();
                    const osg::Vec3d there = osg::Matrixd::inverse(known ? next.view : mGen.mPrev.view).getTrans();
                    if ((there - here).length2() > 512.0 * 512.0)
                        return;
                }
                Variant* variant = mPrograms.builtin("a3 frame generation", sBlitVertex, sWarpFragment);
                if (variant == nullptr || !variant->mOk || variant->mSamplers.size() != 4)
                    return;
                const int count = mGenLock.generated();
                const int span = mGenLock.lastGap();
                const double period = mContext.displayPeriodMs();
                for (int i = 1; i <= count; ++i)
                {
                    float amount = 1.f;
                    osg::Matrixd curToTarget;
                    // shown a refresh after the one before it: placed where the camera is by then
                    const double at = mGenLock.shownAt(i, period);
                    if (known)
                        curToTarget = osg::Matrixd::inverse(cur.view) * viewBetween(cur.view, next.view, at);
                    else
                    {
                        // keeping on as the camera went from the previous frame to this one
                        curToTarget = osg::Matrixd::inverse(osg::Matrixd::inverse(cur.view) * mGen.mPrev.view);
                        amount = static_cast<float>(at * (count + 1) / span);
                    }
                    if (!drawGenerated(*variant, cur, osg::Matrixf(curToTarget), zNear, zFar, amount))
                        return;
                }
            }

            bool drawGenerated(Variant& variant, const FrameGenView& cur, const osg::Matrixf& curToTarget, double zNear,
                double zFar, float amount)
            {
                if (!mContext.beginFrame())
                    return false;
                mResources.resetStream();
                const float black[4] = { 0.f, 0.f, 0.f, 1.f };
                if (!mContext.beginWindowPass(false, black, false, 1.f, 0))
                {
                    mContext.present(0, 0);
                    return false;
                }
                mEncoder = mContext.renderEncoder();
                forgetBindings();
                PipelineState fixed;
                fixed.mFlipY = 0; // the window (Metal's clip space is OpenGL's way up)
                fixed.mColorAttachments = 1;
                fixed.mColorFormat = VK_FORMAT_B8G8R8A8_UNORM;
                fixed.mDepthFormat = VK_FORMAT_D32_SFLOAT_S8_UINT; // the window's depth (Device::mDepthFormat)
                id<MTLRenderPipelineState> pipeline = mPrograms.pipeline(variant, fixed, VertexLayout{});
                const UniformBlock& block = variant.mFragmentUniforms;
                Span span = block.mSize > 0 ? mResources.stream(block.mSize, 256) : Span{};
                if (pipeline == nil || (block.mSize > 0 && span.mData == nullptr))
                {
                    mContext.endWindowPass();
                    mEncoder = nil;
                    mContext.present(0, 0);
                    return false;
                }
                const float proj[4] = { static_cast<float>(cur.projection(0, 0)),
                    static_cast<float>(cur.projection(1, 1)), static_cast<float>(zNear), static_cast<float>(zFar) };
                static const bool tint = std::getenv("OPENMW_FRAMEGEN_TINT") != nullptr;
                // third person: the line from the player's feet (a little above the ground, which is the world's) to
                // above its head, in this frame's view; the radius takes in its arms and what it holds
                float playerFeet[4] = { 0.f, 0.f, 0.f, 0.f }, playerHead[4] = { 0.f, 0.f, 0.f, 0.f };
                if (cur.player && !mGen.mFirstPerson)
                {
                    const osg::Vec3f feet = (cur.playerFeet + osg::Vec3d(0.0, 0.0, cur.playerHeight * 0.06)) * cur.view;
                    const osg::Vec3f head = (cur.playerFeet + osg::Vec3d(0.0, 0.0, cur.playerHeight * 1.15)) * cur.view;
                    playerFeet[0] = feet.x(), playerFeet[1] = feet.y(), playerFeet[2] = feet.z();
                    playerFeet[3] = static_cast<float>(cur.playerRadius * 2.5);
                    playerHead[0] = head.x(), playerHead[1] = head.y(), playerHead[2] = head.z();
                    playerHead[3] = 1.f;
                }
                if (block.mSize > 0)
                    std::memset(span.mData, 0, block.mSize);
                for (const UniformMember& member : block.mMembers)
                {
                    uint8_t* at = span.mData + member.mOffset;
                    if (member.mName == "uCurToPrev")
                        std::memcpy(at, curToTarget.ptr(), 64);
                    else if (member.mName == "uProj")
                        std::memcpy(at, proj, 16);
                    else if (member.mName == "uPlayerFeet")
                        std::memcpy(at, playerFeet, 16);
                    else if (member.mName == "uPlayerHead")
                        std::memcpy(at, playerHead, 16);
                    else
                    {
                        const float value = member.mName == "uReversed" ? (cur.reversedDepth ? 1.f : 0.f)
                            : member.mName == "uAmount"                 ? amount
                            : member.mName == "uTint"                   ? (tint ? 1.f : 0.f)
                            : member.mName == "uFirstPerson"            ? (mGen.mFirstPerson ? 1.f : 0.f)
                                                                        : 0.f;
                        std::memcpy(at, &value, 4);
                    }
                }
                applyEncoderState(fixed, mEmpty);
                [mEncoder setRenderPipelineState:pipeline];
                mBoundPipeline = pipeline;
                if (block.mSize > 0)
                    bindBuffer(1, sStageBlockSlot, span.mBuffer, span.mOffset);
                for (const SamplerSlot& slot : variant.mSamplers)
                {
                    Image* image = slot.mName == "uCur" ? mGen.mScene
                        : slot.mName == "uFinal"        ? mGen.mFinal
                        : slot.mName == "uDepth"        ? mGen.mDepth
                                                        : mGen.mOpaqueDepth;
                    if (slot.mSlot[1] >= 0)
                        bindTexture(1, static_cast<uint32_t>(slot.mSlot[1]), mResources.view(*image, VK_IMAGE_VIEW_TYPE_2D),
                            image->mDepthAspect ? mResources.nearestSampler() : mResources.linearSampler());
                }
                const MTLViewport viewport{ 0.0, 0.0, static_cast<double>(mContext.windowWidth()),
                    static_cast<double>(mContext.windowHeight()), 0.0, 1.0 };
                [mEncoder setViewport:viewport];
                [mEncoder setScissorRect:MTLScissorRect{ 0, 0, mContext.windowWidth(), mContext.windowHeight() }];
                [mEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                mContext.endWindowPass();
                mEncoder = nil;
                forgetBindings();
                mContext.present(0, 0);
                Alpha3::noteShownFrame();
                ++mGenerated;
                return true;
            }

            struct GeneratedFrom
            {
                bool mValid = false;
                FrameGenView mView, mPrev;
                bool mHavePrev = false;
                Image* mScene = nullptr;
                Image* mDepth = nullptr;
                Image* mOpaqueDepth = nullptr;
                Image* mFinal = nullptr;
                bool mFirstPerson = false; // the scene's depth was cleared for the first person model
            } mGen;
            FrameGenLock mGenLock;
            // the launcher's multiplier (2x to 4x): the most generated frames after a rendered one
            int mGenMaxFrames = [] {
                const char* count = std::getenv("OPENMW_FRAMEGEN_FRAMES");
                return count ? std::clamp(std::atoi(count), 1, 3) : 1;
            }();
            uint64_t mGenerated = 0;

            bool mWaitForPrograms = false; // programs compiled at once, not on their thread (variantOf)
            std::unordered_set<const osg::Camera*> mCamerasDrawn[2]; // by frame counter parity (drawStageItself)

            // An attribute as the state sets of a leaf's chain have it, the leaf's own first (the bins' after the root's)
            static const osg::StateAttribute* chainAttribute(const osgUtil::StateGraph* graph,
                const std::vector<const osgUtil::RenderBin*>* bins, osg::StateAttribute::Type type, unsigned member)
            {
                for (; graph != nullptr; graph = graph->_parent)
                    if (const osg::StateSet* stateSet = graph->getStateSet())
                        if (const osg::StateAttribute* attribute = stateSet->getAttribute(type, member))
                            return attribute;
                if (bins != nullptr)
                    for (auto bin = bins->rbegin(); bin != bins->rend(); ++bin)
                        if (const osg::StateSet* stateSet = (*bin)->getStateSet())
                            if (const osg::StateAttribute* attribute = stateSet->getAttribute(type, member))
                                return attribute;
                return nullptr;
            }

            static const osg::Uniform* chainUniform(const osgUtil::StateGraph* graph, const std::string& name)
            {
                for (; graph != nullptr; graph = graph->_parent)
                    if (const osg::StateSet* stateSet = graph->getStateSet())
                        if (const osg::Uniform* uniform = stateSet->getUniform(name))
                            return uniform;
                return nullptr;
            }

            // The buffer of the storage binding at a binding of a chain (a dummy without one)
            struct StorageBinding
            {
                id<MTLBuffer> mBuffer = nil;
                uint64_t mOffset = 0;
            };
            StorageBinding storageInfo(const osgUtil::StateGraph* graph,
                const std::vector<const osgUtil::RenderBin*>* bins, const StorageSlot& slot)
            {
                const auto* binding = static_cast<const osg::BufferIndexBinding*>(chainAttribute(graph, bins,
                    slot.mUniform ? osg::StateAttribute::UNIFORMBUFFERBINDING
                                  : osg::StateAttribute::SHADERSTORAGEBUFFERBINDING,
                    slot.index()));
                // copied on the commands that go ahead of the frame's, so at any time
                const bool copy = true;
                const osg::BufferData* data = binding != nullptr ? binding->getBufferData() : nullptr;
                id<MTLBuffer> buffer = data != nullptr ? mResources.storage(*data, copy) : nil;
                if (buffer == nil)
                    return { mResources.dummyStorage(), 0 };
                const uint64_t total = data->getTotalDataSize();
                const uint64_t offset = std::min<uint64_t>(static_cast<uint64_t>(binding->getOffset()), total);
                return { buffer, offset };
            }

            // The storage blocks of the draw's program, from the leaf's chain, at slots sStorageSlotBase on in the
            // stages that read them
            bool bindStorage(const Variant& variant)
            {
                const size_t count = std::min<size_t>(variant.mStorage.size(), sMaxStorageSlots);
                uint64_t key = 0xcbf29ce484222325ull;
                const auto mix = [&](uint64_t value) { key = (key ^ value) * 0x100000001b3ull; };
                mix(reinterpret_cast<uintptr_t>(&variant));
                mix(reinterpret_cast<uintptr_t>(mChainGraph));
                mix(reinterpret_cast<uintptr_t>(mChainBins));
                if (key == mBoundStorageKey)
                    return true;
                for (size_t i = 0; i < count; ++i)
                {
                    const StorageSlot& slot = variant.mStorage[i];
                    const StorageBinding binding = storageInfo(mChainGraph, mChainBins, slot);
                    const uint32_t index = sStorageSlotBase + static_cast<uint32_t>(i);
                    if (slot.mStages & VK_SHADER_STAGE_VERTEX_BIT)
                        bindBuffer(0, index, binding.mBuffer, binding.mOffset);
                    if (slot.mStages & VK_SHADER_STAGE_FRAGMENT_BIT)
                        bindBuffer(1, index, binding.mBuffer, binding.mOffset);
                }
                mBoundStorageKey = key;
                return true;
            }

            // The stage's osg::DispatchCompute leaves (its own bins', not its pre and post stages'), in the order
            // they are in, each before the draws: GL draws them where they are in the bins, between draws
            void runCompute(const osgUtil::RenderBin& bin)
            {
                for (const auto& [number, child] : bin.getRenderBinList())
                    if (number < 0)
                        runCompute(*child);
                const auto run = [&](const osgUtil::RenderLeaf* leaf) {
                    if (leaf->getDrawable() != nullptr && kindOf(*leaf->getDrawable()) == DrawableKind::Compute)
                        dispatchCompute(*leaf);
                };
                for (const osgUtil::RenderLeaf* leaf : bin.getRenderLeafList())
                    run(leaf);
                for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                    for (const auto& leaf : graph->_leaves)
                        run(leaf.get());
                for (const auto& [number, child] : bin.getRenderBinList())
                    if (number >= 0)
                        runCompute(*child);
            }

            // On the frame's commands, before the stage's pass (Metal orders the buffers' writes and reads itself)
            void dispatchCompute(const osgUtil::RenderLeaf& leaf)
            {
                const auto& dispatch = static_cast<const osg::DispatchCompute&>(*leaf.getDrawable());
                const osgUtil::StateGraph* graph = leaf._parent;
                const auto* program
                    = static_cast<const osg::Program*>(chainAttribute(graph, nullptr, osg::StateAttribute::PROGRAM, 0));
                if (program == nullptr)
                    return;
                ComputeVariant* variant = mPrograms.compute(*program);
                if (variant == nullptr || !variant->mOk || variant->mPipeline == nil)
                    return;
                GLint x = 0, y = 0, z = 0;
                dispatch.getComputeGroups(x, y, z);
                if (x <= 0 || y <= 0 || z <= 0)
                    return;
                const UniformBlock& block = variant->mUniforms;
                Span span;
                if (block.mSize > 0)
                {
                    span = mResources.stream(block.mSize, 16);
                    if (span.mData == nullptr)
                        return;
                    std::memset(span.mData, 0, block.mSize);
                    for (const UniformMember& member : block.mMembers)
                        if (const osg::Uniform* uniform = chainUniform(graph, member.mName))
                            writeUniform(span.mData, block.mSize, member, *uniform);
                }
                mEncoder = nil;
                id<MTLComputeCommandEncoder> encoder = mContext.computeEncoder();
                [encoder setComputePipelineState:variant->mPipeline];
                if (block.mSize > 0)
                    [encoder setBuffer:span.mBuffer offset:span.mOffset atIndex:0];
                const size_t count = std::min<size_t>(variant->mStorage.size(), sMaxStorageSlots);
                for (size_t i = 0; i < count; ++i)
                {
                    const StorageBinding binding = storageInfo(graph, nullptr, variant->mStorage[i]);
                    [encoder setBuffer:binding.mBuffer offset:binding.mOffset atIndex:1 + i];
                }
                [encoder dispatchThreadgroups:MTLSizeMake(x, y, z) threadsPerThreadgroup:variant->mGroup];
                ++mDispatches;
            }

            uint64_t mBoundStorageKey = 0;
            uint64_t mDispatches = 0;

            // The scene (or another image) as it is, onto the current target
            void blitImage(Image& source)
            {
                Variant* variant = mPrograms.builtin("a3 blit", sBlitVertex, sBlitFragment);
                if (variant == nullptr || !variant->mOk)
                    return;
                PipelineState fixed;
                fixed.mFlipY = mFlip ? 1 : 0;
                fixed.mColorAttachments = (mTarget.mWindow || mTarget.mColor) ? (mTarget.mColor1 ? 2 : 1) : 0;
                fixed.mColorFormat = mTarget.mColorFormat;
                fixed.mColorFormat1 = mTarget.mColor1 ? mTarget.mColor1->mFormat : VK_FORMAT_UNDEFINED;
                fixed.mWriteMask1 = 0; // writes no normals
                fixed.mDepthFormat = mTarget.mDepthFormat;
                if (mEncoder == nil || variant->mSamplers.empty())
                    return;
                id<MTLRenderPipelineState> pipeline = mPrograms.pipeline(*variant, fixed, VertexLayout{});
                if (pipeline == nil)
                    return;
                applyEncoderState(fixed, mEmpty);
                [mEncoder setRenderPipelineState:pipeline];
                mBoundPipeline = pipeline;
                const SamplerSlot& slot = variant->mSamplers.front();
                if (slot.mSlot[1] >= 0)
                    bindTexture(1, static_cast<uint32_t>(slot.mSlot[1]), mResources.view(source, VK_IMAGE_VIEW_TYPE_2D),
                        mResources.linearSampler());
                setViewport(0.f, 1.f);
                [mEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                ++mDraws;
            }

            // A render pass into an image of the post processing chain, which becomes the target (loaded, or cleared)
            bool beginImagePass(Image& image, const osg::Vec4f* clear)
            {
                Target target;
                target.mWindow = false;
                target.mColor = &image;
                target.mWidth = image.mWidth;
                target.mHeight = image.mHeight;
                target.mColorFormat = image.mFormat;
                float color[4] = { 0.f, 0.f, 0.f, 0.f };
                if (clear != nullptr)
                    color[0] = clear->r(), color[1] = clear->g(), color[2] = clear->b(), color[3] = clear->a();
                return beginTargetPass(target, { { 0, 0 }, { image.mWidth, image.mHeight } },
                    clear != nullptr ? color : nullptr, false, 1.f, 0, @"post processing");
            }

            void endImagePass()
            {
                mContext.endEncoder();
                mEncoder = nil;
            }

            // The window pass again (what is in it kept), as the stage had it
            bool resumeWindowPass(const Target& target, const VkRect2D& area)
            {
                const float black[4] = { 0.f, 0.f, 0.f, 1.f };
                if (!mContext.beginWindowPass(false, black, false, 1.f, 0))
                    return false;
                mEncoder = mContext.renderEncoder();
                forgetBindings();
                mTarget = target;
                mArea = area;
                mFlip = false;
                mViewportDepth[0] = -1.f;
                setScissor(area);
                setViewport(0.f, 1.f);
                return true;
            }

            // The post processing canvas, as PingPongCanvas::drawImplementation draws it: without techniques the scene
            // as it is; else each technique's passes (with their state sets over the canvas's) into three textures in
            // turn or a pass's own target, the last technique's resolve pass into an image of the window's size, which
            // is drawn into the window (and is what frame generation warps). Not done: the eye adaptation (HDR) and
            // the mipmaps of the passes' targets.
            void drawCanvas(const osgUtil::RenderLeaf& leaf, const std::vector<const osgUtil::RenderBin*>& bins)
            {
                const auto& canvas = static_cast<const PingPongCanvasMirror&>(*leaf.getDrawable());
                Image* scene = canvas.mTextureScene ? mResources.texture(*canvas.mTextureScene) : nullptr;
                if (scene == nullptr)
                    scene = mSceneColor;
                if (scene == nullptr)
                    return;
                std::vector<size_t> filtered;
                for (size_t i = 0; i < canvas.mPasses.size(); ++i)
                    if (!(canvas.mMask & canvas.mPasses[i].mFlags))
                        filtered.push_back(i);
                if (!canvas.mPostprocessing || filtered.empty() || !mTarget.mWindow || !canvas.mTextureScene)
                {
                    blitImage(*scene);
                    return;
                }

                const osg::Texture& sceneTexture = *canvas.mTextureScene;
                const int sceneWidth = sceneTexture.getTextureWidth() > 0 ? sceneTexture.getTextureWidth()
                                                                          : static_cast<int>(scene->mWidth);
                const int sceneHeight = sceneTexture.getTextureHeight() > 0 ? sceneTexture.getTextureHeight()
                                                                            : static_cast<int>(scene->mHeight);
                // the three textures the passes draw into in turn (the canvas's mFbos' copies of the scene texture)
                CanvasTargets& own = mCanvasTargets[&canvas];
                own.mLastUse = mFrameCounter;
                if (own.mSource != &sceneTexture || own.mWidth != sceneWidth || own.mHeight != sceneHeight)
                {
                    for (auto& texture : own.mTextures)
                        texture = static_cast<osg::Texture*>(sceneTexture.clone(osg::CopyOp::SHALLOW_COPY));
                    own.mSource = &sceneTexture;
                    own.mWidth = sceneWidth;
                    own.mHeight = sceneHeight;
                }
                Image* images[3];
                for (int i = 0; i < 3; ++i)
                {
                    images[i] = mResources.renderTarget(
                        *own.mTextures[i], sceneWidth, sceneHeight, sceneTexture.getInternalFormat());
                    if (images[i] == nullptr)
                    {
                        blitImage(*scene);
                        return;
                    }
                }
                const VkExtent2D extent = windowExtent();
                Image* output = mResources.renderTarget(&own, extent.width, extent.height, 0x8058 /* GL_RGBA8 */);
                if (output == nullptr)
                {
                    blitImage(*scene);
                    return;
                }

                const Target windowTarget = mTarget;
                const VkRect2D windowArea = mArea;
                mContext.endWindowPass();
                mEncoder = nil;

                // targets made or resized: cleared to their clear colour (made at the size they ask for now)
                for (const Fx::Types::RenderTarget& attachment : canvas.mDirtyAttachments)
                {
                    if (!attachment.mTarget)
                        continue;
                    const auto [width, height] = attachment.mSize.get(sceneWidth, sceneHeight);
                    attachment.mTarget->setTextureSize(width, height);
                    if (Image* image = mResources.renderTarget(*attachment.mTarget))
                        if (beginImagePass(*image, &attachment.mClearColor))
                            endImagePass();
                }
                canvas.mDirtyAttachments.clear();

                static constexpr std::array<std::array<int, 2>, 3> sBuffers = { { { 1, 2 }, { 0, 2 }, { 0, 1 } } };
                std::array<int, 2> buffer = sBuffers[0];
                int lastDraw = -1; // -1: the scene
                int lastShader = -1;
                bool resolved = false;
                const auto textureOf = [&](int which) -> osg::Texture* {
                    return which < 0 ? canvas.mTextureScene.get() : own.mTextures[which].get();
                };
                for (const size_t index : filtered)
                {
                    const Fx::DispatchNode& node = canvas.mPasses[index];
                    if (!node.mRootStateSet)
                        continue;
                    // the units of apps/openmw/mwrender/postprocessor.hpp (TextureUnits)
                    node.mRootStateSet->setTextureAttribute(2, canvas.mTextureDepth);
                    if (canvas.mTextureNormals)
                        node.mRootStateSet->setTextureAttribute(4, canvas.mTextureNormals);
                    if (canvas.mTextureDistortion)
                        node.mRootStateSet->setTextureAttribute(5, canvas.mTextureDistortion);
                    for (size_t passIndex = 0; passIndex < node.mPasses.size(); ++passIndex)
                    {
                        const Fx::DispatchNode::SubPass& pass = node.mPasses[passIndex];
                        const bool lastPass = passIndex == node.mPasses.size() - 1;
                        pass.mStateSet->setTextureAttribute(0, textureOf(lastShader));
                        pass.mStateSet->setTextureAttribute(1, textureOf(lastDraw));
                        Image* into = nullptr;
                        if (pass.mRenderTarget)
                        {
                            const osg::FrameBufferAttachment& attachment
                                = pass.mRenderTarget->getAttachment(osg::Camera::COLOR_BUFFER0);
                            if (attachment.getTexture() != nullptr)
                                into = mResources.renderTarget(*attachment.getTexture());
                        }
                        else if (pass.mResolve && index == filtered.back())
                        {
                            into = output;
                            resolved = true;
                        }
                        else if (lastPass)
                        {
                            lastDraw = lastShader = buffer[0];
                            into = images[buffer[0]];
                            buffer = sBuffers[lastShader];
                        }
                        else
                        {
                            into = images[buffer[0]];
                            lastDraw = buffer[0];
                            std::swap(buffer[0], buffer[1]);
                        }
                        if (into == nullptr || !beginImagePass(*into, nullptr))
                            continue;
                        Accumulated* state = accumulate(leaf._parent, bins);
                        state = step(state, node.mRootStateSet.get());
                        state = step(state, pass.mStateSet.get());
                        setChain(leaf._parent, &bins, state);
                        drawGeometry(canvas, *state, leaf._modelview.get(), leaf._projection.get());
                        setChain(nullptr, nullptr, nullptr);
                        endImagePass();
                    }
                }

                if (!resumeWindowPass(windowTarget, windowArea))
                    return;
                // without a resolve pass (every pass has a target of its own) the last one drawn, as GL leaves it
                Image* result = resolved ? output : (lastShader >= 0 ? images[lastShader] : scene);
                blitImage(*result);
                if (resolved)
                    mPostColor = output;
            }

            struct CanvasTargets
            {
                const osg::Texture* mSource = nullptr;
                int mWidth = 0, mHeight = 0;
                std::array<osg::ref_ptr<osg::Texture>, 3> mTextures;
                uint64_t mLastUse = 0;
            };
            std::map<const void*, CanvasTargets> mCanvasTargets;
            Image* mPostColor = nullptr; // this frame's post processed scene (before the GUI)

            Context& mContext;
            Resources mResources;
            Programs mPrograms;
            osg::State* mState = nullptr;
            unsigned mFrameNumber = 0;
            uint64_t mFrameCounter = 0;
            uint64_t mNextId = 1;
            uint64_t mStepsNew = 0, mStepsChanged = 0;
            bool mClipLogged = false;
            uint64_t mClippedDraws = 0;
            osg::Vec4f mClipPlanes[2]{ osg::Vec4f(0.f, 0.f, 0.f, 1.f), osg::Vec4f(0.f, 0.f, 0.f, 1.f) };
            std::vector<std::pair<int, const osgUtil::RenderLeaf*>> mNearestFirst; // (kept for its memory)
            // where the water is seen (not known on Metal: reflectionShows)
            bool mWaterKnown = false;
            bool mWaterSeen = false;
            float mWaterRect[4] = { 0.f, 0.f, 1.f, 1.f };
            bool mReflectObjects = true;
            enum class StageKind { Other, Scene, Reflection };
            StageKind mStageKind = StageKind::Other;
            uint64_t mReflectionCulled = 0, mReflectionKept = 0;
            osg::observer_ptr<osg::Stats> mCameraStats; // the main camera's (for the GPU's time)
            double mGpuMsSum = 0.0;
            // the leaf being drawn, for the uniforms of volatile states
            const osgUtil::StateGraph* mChainGraph = nullptr;
            const std::vector<const osgUtil::RenderBin*>* mChainBins = nullptr;
            const Accumulated* mChainState = nullptr;
            Accumulated mEmpty; // id 0
            struct StepKey
            {
                uint64_t mParent;
                uint64_t mHash;
                bool operator==(const StepKey& other) const
                {
                    return mParent == other.mParent && mHash == other.mHash;
                }
            };
            struct StepHash
            {
                size_t operator()(const StepKey& key) const
                {
                    return std::hash<uint64_t>()(key.mParent * 0x9e3779b97f4a7c15ull ^ key.mHash);
                }
            };
            struct Step
            {
                std::shared_ptr<Accumulated> mState;
                const osg::StateSet* mSource = nullptr;
                osg::ref_ptr<const osg::StateSet> mKeep;
                uint64_t mObjects = 0;
                uint64_t mLastUse = 0;
            };
            // this frame's blocks made with volatile uniforms, by what they were made of: where they were streamed
            struct SharedBlock
            {
                id<MTLBuffer> mBuffer = nil;
                uint64_t mOffset = 0;
                uint64_t mFrame = ~0ull;
            };
            std::unordered_map<uint64_t, SharedBlock> mSharedBlocks;
            std::unordered_map<StepKey, Step, StepHash> mSteps;
            std::unordered_map<const std::type_info*, DrawableKind> mDrawableKinds;
            struct GraphHash
            {
                size_t operator()(const std::pair<const osgUtil::StateGraph*, const osgUtil::RenderBin*>& key) const
                {
                    return std::hash<const void*>()(key.first) * 31 + std::hash<const void*>()(key.second);
                }
            };
            struct AccumulatedSlot
            {
                Accumulated* mState = nullptr;
                uint64_t mFrame = ~0ull;
            };
            std::unordered_map<std::pair<const osgUtil::StateGraph*, const osgUtil::RenderBin*>, AccumulatedSlot,
                GraphHash>
                mAccumulated;
            size_t mAccumulatedSweep = 0, mSharedSweep = 0; // the next bucket sweepSlice looks at
            struct GeometryKeyHash
            {
                size_t operator()(const std::pair<const osg::Geometry*, const Variant*>& key) const
                {
                    return std::hash<const void*>()(key.first) * 31 + std::hash<const void*>()(key.second);
                }
            };
            std::unordered_map<std::pair<const osg::Geometry*, const Variant*>, GeometryEntry, GeometryKeyHash>
                mGeometries;
            uint64_t mGeometryVersions = 0;
            osg::ref_ptr<osg::Vec4Array> mConstants[3];
            // what is bound on the encoder (forgotten at each new encoder: forgetBindings)
            id<MTLRenderPipelineState> mBoundPipeline = nil;
            std::vector<DrawTemplate::Bound> mLastImages;
            uint64_t mSameImageBinds = 0;
            uint64_t mVertices = 0;
            uint64_t mPipelineBinds = 0, mVertexBinds = 0, mSetBinds = 0, mUniformBytes = 0, mPipelineCount = 0;
            const GeometryEntry* mBoundVertices = nullptr;
            uint64_t mBoundVerticesVersion = 0;
            std::vector<std::shared_ptr<Accumulated>> mKeep;
            std::set<std::string> mUnknown;
            Image* mSceneColor = nullptr;
            bool mFirstPersonCleared = false; // this frame's scene depth was cleared for the first person model
            Image* mSceneDepth = nullptr; // the depth it was cleared in
            Image* mSceneOpaqueDepth = nullptr; // the copy of the scene's depth before the clear
            bool mInFirstPersonBin = false; // drawing the bin of the first person model (after its depth clear)
            // the scene image of the last frame that drew one, for the loading screen's copy of the window
            Image* mLastSceneColor = nullptr;
            // the water's ripples (renderRipples)
            osg::observer_ptr<osg::Geometry> mRipples;
            Image* mRippleImages[2]{};
            osg::ref_ptr<osg::StateSet> mRipplePasses[2];
            osg::ref_ptr<osg::Uniform> mRippleTime;
            bool mRipplesFailed = false;
            bool mOpaqueDepthWarned = false;
            uint64_t mOpaqueCopies = 0;
            std::set<const osg::Texture*> mDummyLogged;
            // the window of the last frame the loading screen drew
            Image* mLoadingWindow = nullptr;
            uint64_t mLoadingWindowFrame = 0;
            int mLoadingWindowKey = 0;
            struct PendingRead
            {
                osg::ref_ptr<osg::Image> mImage;
                int mWidth = 0;
                int mHeight = 0;
            };
            std::vector<PendingRead> mReads;
            int mReadKey = 0; // the key of the image a read back is copied through
            uint64_t mLastSceneFrame = 0;
            osg::observer_ptr<const osg::Drawable> mCompositeRenderer;
            struct ParticleEntry
            {
                osg::observer_ptr<const osgParticle::ParticleSystem> mSystem;
                osg::ref_ptr<osg::Geometry> mGeometry;
                unsigned mLastUse = 0;
            };
            std::map<const osgParticle::ParticleSystem*, ParticleEntry> mParticles;
            Target mTarget;
            id<MTLRenderCommandEncoder> mEncoder = nil; // the pass being drawn
            VkRect2D mArea{};
            bool mFlip = false;
            float mViewportDepth[2]{ -1.f, -1.f };
            unsigned mDraws = 0, mSkipped = 0;
            osg::Timer_t mLastReport = 0;
            double mRecordSeconds = 0.0, mWaitSeconds = 0.0;
        };

        std::unique_ptr<Translator> sTranslator;
    }

    void translateFrame(Context& context, osgUtil::SceneView& sceneView)
    {
        if (!sTranslator)
            sTranslator = std::make_unique<Translator>(context);
        sTranslator->frame(sceneView);
    }
}
