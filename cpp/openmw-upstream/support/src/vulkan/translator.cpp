// The frame OSG culled, drawn with Vulkan. The render stages are walked as RenderStage::draw does (pre-render stages,
// the stage, post-render stages), and their bins as RenderBin::drawImplementation does; each leaf's state is the
// StateSets along its StateGraph (the bins' own after the root's), applied with OSG's rules for OVERRIDE and PROTECTED.
// From that state come the program (a pipeline per fixed function state and vertex layout), its uniforms (a uniform
// block per stage, filled from OSG's uniforms by name) and its textures (by the unit its sampler uniform names).
//
// A camera that draws into textures (an FBO) gets images made for them, which shaders then sample. Render targets are
// drawn the OpenGL way up (row 0 at the bottom), so that the shaders' texture coordinates and gl_FragCoord read them as
// they do under OpenGL ES; the window alone is drawn turned over (its row 0 is at the top).
//
// Drawables are osg::Geometry and its kind. The engine's own drawables that draw with OpenGL calls have stand-ins here:
// MyGUI's batches and the post-processing canvas (which shows the scene's texture). Those the translator does not know
// are left out (logged once each).
#include "translator.hpp"

#include "context.hpp"
#include "programs.hpp"
#include "resources.hpp"

#include "../support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <cstring>
#include <cmath>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <typeinfo>
#include <unordered_map>

#include <osg/BlendColor>
#include <osg/BlendEquation>
#include <osg/BlendFunc>
#include <osg/ClipPlane>
#include <osg/ColorMask>
#include <osg/CullFace>
#include <osg/Depth>
#include <osg/FrameBufferObject>
#include <osg/FrontFace>
#include <osg/Geometry>
#include <osg/Image>
#include <osg/PolygonOffset>
#include <osg/Program>
#include <osg/Stencil>
#include <osg/Texture>
#include <osg/Texture2D>
#include <osg/Uniform>
#include <osgUtil/RenderStage>
#include <osgUtil/SceneView>
#include <osgUtil/StateGraph>
#include <osgViewer/ViewerBase>
#include <osgParticle/ParticleSystem>

#include <components/resource/resourcesystem.hpp>
#include <components/resource/scenemanager.hpp>
#include <components/shader/shadermanager.hpp>
#include <components/terrain/compositemaprenderer.hpp>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Vk
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
            std::vector<uint8_t> mBlocks[2]; // the vertex and fragment stage's uniforms, but for the per draw ones
            struct Patch
            {
                int mStage;
                uint32_t mOffset;
                int mWhich; // 0 model view, 1 projection, 2 model view projection, 3 normal
            };
            std::vector<Patch> mPatches;
            std::vector<VkDescriptorImageInfo> mImages; // the samplers' elements in binding order
            std::vector<std::pair<std::array<VkBuffer, 3>, VkDescriptorSet>> mSets;
            // the blocks of a volatile state's draws this frame, by the state sets their uniforms came from
            struct Copy
            {
                uint64_t mKey;
                VkBuffer mBuffer[2];
                VkDeviceSize mOffset[2];
            };
            std::vector<Copy> mCopies;

            // What the blocks and images are made from again each frame (and for each target): the uniforms' values,
            // over the initializers' (mBase), and the textures of the samplers' elements
            std::vector<uint8_t> mBase[2];
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
            VkBuffer mStatic[2]{};
            VkDeviceSize mStaticOffset[2]{};
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
                return found == mTextures[unit].end() ? nullptr
                                                      : dynamic_cast<const osg::Texture*>(found->second.first);
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
            state.mProgram = dynamic_cast<const osg::Program*>(state.attribute(osg::StateAttribute::PROGRAM));
            PipelineState& fixed = state.mFixed;

            fixed.mDepthTest = state.mode(0x0B71, false); // GL_DEPTH_TEST
            fixed.mDepthWrite = 1;
            fixed.mDepthCompare = VK_COMPARE_OP_LESS;
            if (auto* depth = dynamic_cast<const osg::Depth*>(state.attribute(osg::StateAttribute::DEPTH)))
            {
                fixed.mDepthCompare = compare(depth->getFunction());
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
            if (auto* func = dynamic_cast<const osg::BlendFunc*>(state.attribute(osg::StateAttribute::BLENDFUNC)))
            {
                fixed.mSrcColor = blendFactor(func->getSourceRGB());
                fixed.mDstColor = blendFactor(func->getDestinationRGB());
                fixed.mSrcAlpha = blendFactor(func->getSourceAlpha());
                fixed.mDstAlpha = blendFactor(func->getDestinationAlpha());
            }
            if (auto* equation
                = dynamic_cast<const osg::BlendEquation*>(state.attribute(osg::StateAttribute::BLENDEQUATION)))
            {
                fixed.mColorOp = blendOp(equation->getEquationRGB());
                fixed.mAlphaOp = blendOp(equation->getEquationAlpha());
            }
            if (auto* color = dynamic_cast<const osg::BlendColor*>(state.attribute(osg::StateAttribute::BLENDCOLOR)))
                for (int i = 0; i < 4; ++i)
                    state.mBlendConstants[i] = color->getConstantColor()[i];
            if (!fixed.mBlend)
            {
                fixed.mSrcColor = fixed.mSrcAlpha = VK_BLEND_FACTOR_ONE;
                fixed.mDstColor = fixed.mDstAlpha = VK_BLEND_FACTOR_ZERO;
                fixed.mColorOp = fixed.mAlphaOp = VK_BLEND_OP_ADD;
            }

            if (auto* mask = dynamic_cast<const osg::ColorMask*>(state.attribute(osg::StateAttribute::COLORMASK)))
                fixed.mWriteMask = (mask->getRedMask() ? 1 : 0) | (mask->getGreenMask() ? 2 : 0)
                    | (mask->getBlueMask() ? 4 : 0) | (mask->getAlphaMask() ? 8 : 0);

            fixed.mCullMode = VK_CULL_MODE_NONE;
            if (state.mode(0x0B44, false)) // GL_CULL_FACE
            {
                fixed.mCullMode = VK_CULL_MODE_BACK_BIT;
                if (auto* cull = dynamic_cast<const osg::CullFace*>(state.attribute(osg::StateAttribute::CULLFACE)))
                    fixed.mCullMode = cull->getMode() == osg::CullFace::FRONT ? VK_CULL_MODE_FRONT_BIT
                        : cull->getMode() == osg::CullFace::FRONT_AND_BACK    ? VK_CULL_MODE_FRONT_AND_BACK
                                                                              : VK_CULL_MODE_BACK_BIT;
            }
            if (auto* front = dynamic_cast<const osg::FrontFace*>(state.attribute(osg::StateAttribute::FRONTFACE)))
                state.mGlCounterClockwise = front->getMode() == osg::FrontFace::COUNTER_CLOCKWISE;

            if (state.mode(0x8037, false)) // GL_POLYGON_OFFSET_FILL
                if (auto* offset
                    = dynamic_cast<const osg::PolygonOffset*>(state.attribute(osg::StateAttribute::POLYGONOFFSET)))
                {
                    fixed.mDepthBias = 1;
                    fixed.mBiasConstant = offset->getUnits();
                    fixed.mBiasSlope = offset->getFactor();
                }

            if (state.mode(0x0B90, false)) // GL_STENCIL_TEST
                if (auto* stencil = dynamic_cast<const osg::Stencil*>(state.attribute(osg::StateAttribute::STENCIL)))
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

        constexpr uint32_t sDrawBlockSize = 272; // a3DrawUniforms: three mat4, a mat3 and two clip planes, std140

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
                case 9: // GL_POLYGON (convex): a fan
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
                case 7: // GL_QUADS: two triangles each
                    convert = true;
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                default:
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            }
        }

        // A list of quads or a line loop as the indices of triangles or of a strip
        void convertIndices(unsigned mode, const std::vector<uint32_t>& in, std::vector<uint32_t>& out)
        {
            if (mode == 7)
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

        VkFormat vertexFormat(const osg::Array& array, bool integer)
        {
            const unsigned size = array.getDataSize();
            if (size < 1 || size > 4)
                return VK_FORMAT_UNDEFINED;
            const bool normalize = array.getNormalize();
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
                ++mFrameCounter;
                mAccumulated.clear();
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
                gpuTimesBegin();
                copyFramebufferCallbacks(sceneView);
                renderCompositeMaps();
                renderRipples();
                if (osgUtil::RenderStage* stage = sceneView.getRenderStage())
                    drawStage(*stage);
                mContext.endWindowPass();
                keepLoadingWindow(sceneView);
                readImages();
                runFinalDrawCallbacks(sceneView, true);
                gpuTimestamp(nullptr);
                if (mSceneColor != nullptr)
                {
                    mLastSceneColor = mSceneColor;
                    mLastSceneFrame = mFrameCounter;
                }
                mRecordSeconds += osg::Timer::instance()->delta_s(begun, osg::Timer::instance()->tick());
                if (mContext.frameNumber() % 300 == 30)
                {
                    const osg::Timer_t now = osg::Timer::instance()->tick();
                    const double seconds = mLastReport ? osg::Timer::instance()->delta_s(mLastReport, now) : 0.0;
                    Log(Debug::Info) << "Vulkan: frame " << mContext.frameNumber() << ": " << mDraws << " draws, "
                                     << mSkipped << " left out, "
                                     << (seconds > 0 ? 300.0 / seconds : 0.0) << " fps; per frame "
                                     << mRecordSeconds / 300 * 1000 << " ms recording, " << mWaitSeconds / 300 * 1000
                                     << " ms waiting for the GPU; states " << mSteps.size() << ", "
                                     << mStepsNew / 300 << " new and " << mStepsChanged / 300
                                     << " made volatile a frame; " << mResources.mStreamedBytes / 300 / 1024
                                     << " KiB streamed a frame; " << mClippedDraws / 300 << " clipped draws a frame; "
                                     << mOpaqueCopies << " opaque copies";
                    mClippedDraws = 0;
                    mOpaqueCopies = 0;
                    mStepsNew = mStepsChanged = 0;
                    if (!mGpuSums.empty())
                    {
                        std::ostringstream out;
                        out << "Vulkan: GPU ms a frame:";
                        for (const auto& [label, ms] : mGpuSums)
                            out << " " << label << " " << ms / 300;
                        Log(Debug::Info) << out.str();
                        mGpuSums.clear();
                    }
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
                if (stage == nullptr || mPass == VK_NULL_HANDLE || mTarget.mWindow || mTarget.mColor == nullptr)
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
                    if (!Alpha3::opaqueCopyWanted(bin, info))
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

                VkCommandBuffer commands = mContext.commands();
                vkCmdEndRenderPass(commands);
                ++mOpaqueCopies;
                if (color != nullptr)
                    blit(*mTarget.mColor, *color);
                if (depth != nullptr && mTarget.mDepth != nullptr)
                {
                    if (depth->mFormat == mTarget.mDepth->mFormat && depth->mWidth == mTarget.mDepth->mWidth
                        && depth->mHeight == mTarget.mDepth->mHeight)
                        copyDepth(*mTarget.mDepth, *depth);
                    else if (!mOpaqueDepthWarned)
                    {
                        mOpaqueDepthWarned = true;
                        Log(Debug::Warning) << "Vulkan: the opaque depth copy has another format or size than the "
                                               "scene's depth; not copied";
                    }
                }

                // the pass again, loading what was drawn
                AttachmentUse colorUse, depthUse;
                std::vector<VkImageView> views;
                colorUse.mFormat = mTarget.mColor->mFormat;
                colorUse.mLoad = VK_ATTACHMENT_LOAD_OP_LOAD;
                colorUse.mInitial = colorUse.mFinal = mTarget.mColor->readLayout();
                views.push_back(mTarget.mColor->mAttachmentView);
                if (mTarget.mDepth)
                {
                    depthUse.mFormat = mTarget.mDepth->mFormat;
                    depthUse.mLoad = VK_ATTACHMENT_LOAD_OP_LOAD;
                    depthUse.mInitial = depthUse.mFinal = mTarget.mDepth->readLayout();
                    views.push_back(mTarget.mDepth->mAttachmentView);
                }
                VkRenderPass pass = mContext.renderPass(colorUse, depthUse);
                VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
                begin.renderPass = pass;
                begin.framebuffer = mResources.framebuffer(pass, views, mTarget.mWidth, mTarget.mHeight);
                begin.renderArea = mArea;
                vkCmdBeginRenderPass(commands, &begin, VK_SUBPASS_CONTENTS_INLINE);
                forgetBindings();
                mPass = pass;
                mViewportDepth[0] = -1.f;
                setViewport(0.f, 1.f);
                const VkRect2D scissor = scissorOf(mArea);
                vkCmdSetScissor(commands, 0, 1, &scissor);
            }

            void copyDepth(Image& source, Image& target)
            {
                VkCommandBuffer commands = mContext.commands();
                const VkImageAspectFlags aspect
                    = VK_IMAGE_ASPECT_DEPTH_BIT | (source.mStencilAspect ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
                const VkPipelineStageFlags tests
                    = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
                const VkPipelineStageFlags shaders
                    = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                auto barrier = [&](Image& image, VkImageLayout from, VkImageLayout to, VkAccessFlags fromAccess,
                                   VkAccessFlags toAccess, VkPipelineStageFlags fromStage, VkPipelineStageFlags toStage) {
                    VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
                    b.oldLayout = from;
                    b.newLayout = to;
                    b.srcAccessMask = fromAccess;
                    b.dstAccessMask = toAccess;
                    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    b.image = image.mImage;
                    b.subresourceRange = { aspect, 0, 1, 0, 1 };
                    vkCmdPipelineBarrier(commands, fromStage, toStage, 0, 0, nullptr, 0, nullptr, 1, &b);
                };
                barrier(source, source.readLayout(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, tests,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);
                barrier(target, target.readLayout(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, shaders, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkImageCopy region{};
                region.srcSubresource = { aspect, 0, 0, 1 };
                region.dstSubresource = region.srcSubresource;
                region.extent = { source.mWidth, source.mHeight, 1 };
                vkCmdCopyImage(commands, source.mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.mImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
                barrier(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, source.readLayout(), VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, tests);
                barrier(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, target.readLayout(), VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, shaders);
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
                    const VkExtent2D window = mContext.windowExtent();
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
                const VkExtent2D window = mContext.windowExtent();
                Image* image = mResources.renderTarget(&mLoadingWindowKey, window.width, window.height, 0x8058);
                if (image == nullptr
                    || !mContext.blitWindow(image->mImage, image->mWidth, image->mHeight, image->readLayout()))
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
                    Log(Debug::Warning) << "Vulkan: no scene image to read back for a screenshot";
                    return;
                }
                Image* copy = mResources.renderTarget(&mReadKey, scene->mWidth, scene->mHeight, 0x8058);
                if (copy == nullptr)
                    return;
                blit(*scene, *copy);
                const uint32_t width = copy->mWidth, height = copy->mHeight;
                Buffer buffer = mResources.readbackBuffer(VkDeviceSize(width) * height * 4);
                if (buffer.mBuffer == VK_NULL_HANDLE)
                    return;
                VkCommandBuffer commands = mContext.commands();
                VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
                b.oldLayout = copy->readLayout();
                b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                b.image = copy->mImage;
                b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                    nullptr, 0, nullptr, 1, &b);
                VkBufferImageCopy region{};
                region.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                region.imageExtent = { width, height, 1 };
                vkCmdCopyImageToBuffer(
                    commands, copy->mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer.mBuffer, 1, &region);
                b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                b.newLayout = copy->readLayout();
                b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0,
                    nullptr, 1, &b);
                VkMemoryBarrier host{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
                host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host,
                    0, nullptr, 0, nullptr);
                Resources* resources = &mResources;
                mContext.afterFrame([resources, buffer, width, height, reads] {
                    const uint8_t* pixels = resources->read(buffer);
                    if (pixels == nullptr)
                        return;
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

            void blit(Image& source, Image& target)
            {
                VkCommandBuffer commands = mContext.commands();
                auto barrier = [&](Image& image, VkImageLayout from, VkImageLayout to, VkAccessFlags fromAccess,
                                   VkAccessFlags toAccess, VkPipelineStageFlags fromStage, VkPipelineStageFlags toStage) {
                    VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
                    b.oldLayout = from;
                    b.newLayout = to;
                    b.srcAccessMask = fromAccess;
                    b.dstAccessMask = toAccess;
                    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    b.image = image.mImage;
                    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                    vkCmdPipelineBarrier(commands, fromStage, toStage, 0, 0, nullptr, 0, nullptr, 1, &b);
                };
                const VkPipelineStageFlags shaders
                    = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                barrier(source, source.readLayout(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | shaders, VK_PIPELINE_STAGE_TRANSFER_BIT);
                barrier(target, target.readLayout(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, shaders, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkImageBlit region{};
                region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
                region.dstSubresource = region.srcSubresource;
                region.srcOffsets[1] = { int32_t(source.mWidth), int32_t(source.mHeight), 1 };
                region.dstOffsets[1] = { int32_t(target.mWidth), int32_t(target.mHeight), 1 };
                vkCmdBlitImage(commands, source.mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.mImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);
                barrier(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, source.readLayout(), VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, shaders);
                barrier(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, target.readLayout(), VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, shaders);
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
                    const VkExtent2D extent = mContext.windowExtent();
                    target.mWidth = extent.width;
                    target.mHeight = extent.height;
                    target.mColorFormat = mContext.windowFormat();
                    target.mDepthFormat = mContext.windowDepthFormat();
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
                target.mColorFormat = target.mColor ? target.mColor->mFormat : VK_FORMAT_UNDEFINED;
                target.mDepthFormat = target.mDepth ? target.mDepth->mFormat : VK_FORMAT_UNDEFINED;
                return true;
            }

            void drawStageItself(osgUtil::RenderStage& stage)
            {
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
                const bool whole = area.offset.x <= 0 && area.offset.y <= 0
                    && area.offset.x + static_cast<int32_t>(area.extent.width) >= static_cast<int32_t>(target.mWidth)
                    && area.offset.y + static_cast<int32_t>(area.extent.height) >= static_cast<int32_t>(target.mHeight);

                const osg::Vec4& color = stage.getClearColor();
                const float clearValues[4] = { color.r(), color.g(), color.b(), color.a() };
                const float depthValue = static_cast<float>(stage.getClearDepth());
                const uint32_t stencilValue = static_cast<uint32_t>(stage.getClearStencil());
                VkCommandBuffer commands = mContext.commands();

                mContext.endWindowPass();
                if (target.mWindow)
                {
                    if (!mContext.beginWindowPass(clearColor && whole, clearValues, (clearDepth || clearStencil) && whole,
                            depthValue, stencilValue))
                        return;
                    mPass = mContext.windowCompatiblePass();
                }
                else
                {
                    AttachmentUse colorUse, depthUse;
                    std::vector<VkImageView> views;
                    std::vector<VkClearValue> clears;
                    if (target.mColor)
                    {
                        colorUse.mFormat = target.mColor->mFormat;
                        colorUse.mLoad = clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
                        colorUse.mInitial = colorUse.mFinal = target.mColor->readLayout();
                        views.push_back(target.mColor->mAttachmentView);
                        VkClearValue value{};
                        value.color = { { clearValues[0], clearValues[1], clearValues[2], clearValues[3] } };
                        clears.push_back(value);
                    }
                    if (target.mDepth)
                    {
                        depthUse.mFormat = target.mDepth->mFormat;
                        depthUse.mLoad = (clearDepth || clearStencil) ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                                                      : VK_ATTACHMENT_LOAD_OP_LOAD;
                        depthUse.mInitial = depthUse.mFinal = target.mDepth->readLayout();
                        views.push_back(target.mDepth->mAttachmentView);
                        VkClearValue value{};
                        value.depthStencil = { depthValue, stencilValue };
                        clears.push_back(value);
                    }
                    VkRenderPass pass = mContext.renderPass(colorUse, depthUse);
                    VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
                    begin.renderPass = pass;
                    begin.framebuffer = mResources.framebuffer(pass, views, target.mWidth, target.mHeight);
                    // only the viewport: a tile of a shadow atlas loads, clears and stores its own pixels, not the
                    // whole atlas (the clear, held to the viewport by OSG, is the pass's)
                    begin.renderArea = area;
                    begin.clearValueCount = static_cast<uint32_t>(clears.size());
                    begin.pClearValues = clears.data();
                    const osg::Camera* camera = stage.getCamera();
                    gpuTimestamp((camera && !camera->getName().empty() ? camera->getName() : std::string("RTT")) + " "
                        + std::to_string(area.extent.width) + "x" + std::to_string(area.extent.height));
                    vkCmdBeginRenderPass(commands, &begin, VK_SUBPASS_CONTENTS_INLINE);
                    forgetBindings();
                    mPass = pass;
                }
                mTarget = target;
                mArea = area;
                mFlip = target.mWindow;
                mViewportDepth[0] = -1.f;

                // a clear of a part of the target
                if (target.mWindow && !whole && (clearColor || clearDepth || clearStencil))
                {
                    std::vector<VkClearAttachment> attachments;
                    if (clearColor && (target.mWindow || target.mColor))
                    {
                        VkClearAttachment attachment{ VK_IMAGE_ASPECT_COLOR_BIT, 0, {} };
                        attachment.clearValue.color
                            = { { clearValues[0], clearValues[1], clearValues[2], clearValues[3] } };
                        attachments.push_back(attachment);
                    }
                    if ((clearDepth || clearStencil) && (target.mWindow || target.mDepth))
                    {
                        VkImageAspectFlags aspect = 0;
                        if (clearDepth)
                            aspect |= VK_IMAGE_ASPECT_DEPTH_BIT;
                        if (clearStencil && hasStencil(target.mDepthFormat))
                            aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
                        VkClearAttachment attachment{ aspect, 0, {} };
                        attachment.clearValue.depthStencil = { depthValue, stencilValue };
                        if (aspect != 0)
                            attachments.push_back(attachment);
                    }
                    VkClearRect rect{ scissorOf(area), 0, 1 };
                    if (!attachments.empty() && rect.rect.extent.width > 0 && rect.rect.extent.height > 0)
                        vkCmdClearAttachments(
                            commands, static_cast<uint32_t>(attachments.size()), attachments.data(), 1, &rect);
                }

                setViewport(0.f, 1.f);
                const VkRect2D scissor = scissorOf(area);
                vkCmdSetScissor(commands, 0, 1, &scissor);

                stageClipPlanes(stage);
                std::vector<const osgUtil::RenderBin*> bins;
                drawBin(stage, bins);

                if (target.mWindow)
                    mContext.endWindowPass();
                else
                {
                    vkCmdEndRenderPass(commands);
                    gpuTimestamp("");
                }
                mPass = VK_NULL_HANDLE;
            }

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
                            Log(Debug::Info) << "Vulkan: clip plane " << clip->getClipPlaneNum() << " ("
                                             << (camera ? camera->getName() : std::string()) << ") eye space "
                                             << plane.asVec4().x() << " " << plane.asVec4().y() << " "
                                             << plane.asVec4().z() << " " << plane.asVec4().w();
                        }
                    }
                };
                apply(stage.getInheritedPositionalStateContainer(), &stage.getInheritedPositionalStateContainerMatrix());
                apply(stage.getPositionalStateContainer(), nullptr);
            }

            // A rectangle of the target as Vulkan's rows go (the window's turned over)
            VkRect2D scissorOf(const VkRect2D& area) const
            {
                VkRect2D rect = area;
                if (mFlip)
                    rect.offset.y = static_cast<int32_t>(mTarget.mHeight) - area.offset.y
                        - static_cast<int32_t>(area.extent.height);
                rect.offset.x = std::max(rect.offset.x, 0);
                rect.offset.y = std::max(rect.offset.y, 0);
                rect.extent.width = std::min(rect.extent.width, mTarget.mWidth - static_cast<uint32_t>(rect.offset.x));
                rect.extent.height
                    = std::min(rect.extent.height, mTarget.mHeight - static_cast<uint32_t>(rect.offset.y));
                return rect;
            }

            void setViewport(float minDepth, float maxDepth)
            {
                if (mViewportDepth[0] == minDepth && mViewportDepth[1] == maxDepth)
                    return;
                mViewportDepth[0] = minDepth;
                mViewportDepth[1] = maxDepth;
                VkViewport viewport{};
                viewport.x = static_cast<float>(mArea.offset.x);
                viewport.y = mFlip ? static_cast<float>(static_cast<int32_t>(mTarget.mHeight) - mArea.offset.y
                                         - static_cast<int32_t>(mArea.extent.height))
                                   : static_cast<float>(mArea.offset.y);
                viewport.width = static_cast<float>(mArea.extent.width);
                viewport.height = static_cast<float>(mArea.extent.height);
                viewport.minDepth = std::clamp(minDepth, 0.f, 1.f);
                viewport.maxDepth = std::clamp(maxDepth, 0.f, 1.f);
                vkCmdSetViewport(mContext.commands(), 0, 1, &viewport);
            }

            void drawBin(const osgUtil::RenderBin& bin, std::vector<const osgUtil::RenderBin*>& bins)
            {
                bins.push_back(&bin);
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
                        VkClearAttachment attachment{ VK_IMAGE_ASPECT_DEPTH_BIT, 0, {} };
                        attachment.clearValue.depthStencil = { 1.f, 0 };
                        VkClearRect rect{ scissorOf(mArea), 0, 1 };
                        vkCmdClearAttachments(mContext.commands(), 1, &attachment, 1, &rect);
                    }
                }
                auto child = bin.getRenderBinList().begin();
                for (; child != bin.getRenderBinList().end() && child->first < 0; ++child)
                    drawBin(*child->second, bins);
                for (const osgUtil::RenderLeaf* leaf : bin.getRenderLeafList())
                    drawLeaf(*leaf, bins);
                for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                    for (const auto& leaf : graph->_leaves)
                        drawLeaf(*leaf, bins);
                for (; child != bin.getRenderBinList().end(); ++child)
                    drawBin(*child->second, bins);
                bins.pop_back();
            }

            // The state of a leaf's StateGraph with the bins' StateSets put in after its root's
            // A hash of what a state set holds: the attributes, modes, textures and uniforms (by object) and the defines
            static uint64_t fingerprint(const osg::StateSet& stateSet, bool uniformObjects)
            {
                uint64_t hash = 0xcbf29ce484222325ull;
                const auto mix = [&](uint64_t value) {
                    hash ^= value + 0x9e3779b97f4a7c15ull + (hash << 6) + (hash >> 2);
                };
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
                for (const auto& [name, ref] : stateSet.getUniformList())
                {
                    mix(std::hash<std::string>()(name));
                    if (uniformObjects)
                        mix(reinterpret_cast<uintptr_t>(ref.first.get()));
                    mix(ref.second);
                }
                for (const auto& [name, define] : stateSet.getDefineList())
                {
                    mix(std::hash<std::string>()(name));
                    mix(std::hash<std::string>()(define.first));
                    mix(define.second);
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
                uint64_t hash = stateSet != nullptr ? fingerprint(*stateSet, false)
                                                    : reinterpret_cast<uintptr_t>(texture) * 0x9e3779b97f4a7c15ull + 0x7e47;
                if (stateSet == nullptr)
                    hash ^= 0x5bd1e9955bd1e995ull;
                Step& entry = mSteps[StepKey{ parent->mId, hash }];
                entry.mLastUse = mFrameCounter;
                const uint64_t objects = stateSet != nullptr ? fingerprint(*stateSet, true) : 0;
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
                auto found = mAccumulated.find(key);
                if (found != mAccumulated.end())
                    return found->second;
                Accumulated* state;
                if (graph->_parent == nullptr)
                {
                    state = step(&mEmpty, graph->getStateSet());
                    for (const osgUtil::RenderBin* bin : bins)
                        state = step(state, bin->getStateSet());
                }
                else
                    state = step(accumulate(graph->_parent, bins), graph->getStateSet());
                mAccumulated.emplace(key, state);
                return state;
            }

            // GPU timestamps: one at the frame's start and end, and around each render target's pass, read back when
            // the frame slot comes round again; summed by label (the window's passes are what is left of the frame)
            static constexpr uint32_t sGpuQueries = 512;
            void gpuTimesBegin()
            {
                const unsigned slot = mContext.frameSlot();
                VkDevice device = mContext.vkDevice();
                if (mGpuPools[slot] == VK_NULL_HANDLE)
                {
                    VkQueryPoolCreateInfo info{ VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
                    info.queryType = VK_QUERY_TYPE_TIMESTAMP;
                    info.queryCount = sGpuQueries;
                    if (vkCreateQueryPool(device, &info, nullptr, &mGpuPools[slot]) != VK_SUCCESS)
                        return;
                }
                else if (mGpuUsed[slot] >= 2)
                {
                    std::vector<uint64_t> ticks(mGpuUsed[slot]);
                    if (vkGetQueryPoolResults(device, mGpuPools[slot], 0, mGpuUsed[slot], ticks.size() * 8,
                            ticks.data(), 8, VK_QUERY_RESULT_64_BIT)
                        == VK_SUCCESS)
                    {
                        const double period = mContext.device().mProperties.limits.timestampPeriod * 1e-6;
                        const std::vector<std::string>& labels = mGpuLabels[slot];
                        double inPasses = 0;
                        for (uint32_t i = 1; i + 1 < mGpuUsed[slot]; i += 2)
                        {
                            const double ms = (ticks[i + 1] - ticks[i]) * period;
                            mGpuSums[labels[i]] += ms;
                            inPasses += ms;
                        }
                        const double total = (ticks[mGpuUsed[slot] - 1] - ticks[0]) * period;
                        mGpuSums["frame"] += total;
                        mGpuSums["window+rest"] += total - inPasses;
                    }
                }
                mGpuUsed[slot] = 0;
                mGpuLabels[slot].clear();
                vkCmdResetQueryPool(mContext.commands(), mGpuPools[slot], 0, sGpuQueries);
                gpuTimestamp("start");
            }

            // label: a pass starting ("" a pass ending), nullptr the frame's end
            void gpuTimestamp(const std::string* label) { gpuTimestamp(label ? *label : std::string("end")); }
            void gpuTimestamp(const std::string& label)
            {
                const unsigned slot = mContext.frameSlot();
                if (mGpuPools[slot] == VK_NULL_HANDLE || mGpuUsed[slot] >= sGpuQueries - 1)
                    return;
                vkCmdWriteTimestamp(mContext.commands(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, mGpuPools[slot],
                    mGpuUsed[slot]++);
                mGpuLabels[slot].push_back(label);
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
                if (state.mVariant == nullptr || state.mVariantProgram != state.mProgram)
                {
                    state.mVariant = mPrograms.variant(*state.mProgram, state.mDefines, *mState);
                    state.mVariantProgram = state.mProgram;
                }
                return state.mVariant;
            }

            void noteUnknown(const osg::Drawable& drawable)
            {
                ++mSkipped;
                const std::string type = typeid(drawable).name();
                if (mUnknown.insert(type).second)
                    Log(Debug::Info) << "Vulkan: drawables of type " << type << " (" << drawable.className()
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

            void drawLeafItself(const osgUtil::RenderLeaf& leaf, const std::vector<const osgUtil::RenderBin*>& bins)
            {
                const osg::Drawable* drawable = leaf.getDrawable();
                if (drawable == nullptr || leaf._parent == nullptr)
                    return;
                const std::string type = typeid(*drawable).name();
                if (std::strcmp(drawable->libraryName(), "osgMyGUI") == 0
                    && std::strcmp(drawable->className(), "Drawable") == 0)
                {
                    drawMyGui(static_cast<const MyGuiDrawable&>(*drawable), leaf, bins);
                    return;
                }
                if (std::strcmp(drawable->className(), "CompositeMapRenderer") == 0
                    || type.find("CompositeMapRenderer") != std::string::npos)
                {
                    mCompositeRenderer = static_cast<const CompositeMapQueues*>(drawable);
                    return;
                }
                if (auto* particles = dynamic_cast<const osgParticle::ParticleSystem*>(drawable))
                {
                    drawParticles(*particles, leaf, bins);
                    return;
                }
                if (type.find("PingPongCanvas") != std::string::npos)
                {
                    drawCanvas();
                    return;
                }
                if (const osg::Drawable::DrawCallback* callback = drawable->getDrawCallback();
                    callback != nullptr && type == "N3osg8DrawableE"
                    && std::strstr(typeid(*callback).name(), "ReadImageFromFramebufferCallback") != nullptr)
                {
                    const auto& read = static_cast<const ReadImageCallback&>(*callback);
                    if (read.mImage)
                        mReads.push_back({ read.mImage, read.mWidth, read.mHeight });
                    return;
                }
                const osg::Geometry* geometry = drawable->asGeometry();
                if (geometry == nullptr || type.find("RipplesSurface") != std::string::npos)
                {
                    noteUnknown(*drawable);
                    return;
                }
                Accumulated* state = accumulate(leaf._parent, bins);
                setChain(leaf._parent, &bins, state);
                drawGeometry(*geometry, *state, leaf._modelview.get(), leaf._projection.get());
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
                const UniformBlock* blocks[2] = { &variant.mVertexUniforms, &variant.mFragmentUniforms };
                for (int stage = 0; stage < 2; ++stage)
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
                draw.mSets.clear();
                draw.mCopies.clear();
                draw.mStatic[0] = draw.mStatic[1] = VK_NULL_HANDLE;
                const Variant& variant = *draw.mVariant;
                const UniformBlock* blocks[2] = { &variant.mVertexUniforms, &variant.mFragmentUniforms };
                for (int stage = 0; stage < 2; ++stage)
                    draw.mBlocks[stage] = draw.mBase[stage];
                for (const DrawTemplate::Source& source : draw.mSources)
                    writeUniform(draw.mBlocks[source.mStage].data(), blocks[source.mStage]->mSize, *source.mMember,
                        *source.mUniform);
                draw.mVolatile.clear();
                for (const DrawTemplate::Source& source : draw.mSources)
                    if (source.mOwner->mVolatile)
                        draw.mVolatile.push_back(&source);
                draw.mImages.clear();
                for (const DrawTemplate::ImageSource& source : draw.mImageSources)
                {
                    const osg::Texture* texture = source.mTexture;
                    Image* image = texture ? mResources.texture(*texture) : nullptr;
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
                        Log(Debug::Verbose) << "Vulkan: texture drawn as the dummy: " << texture->className() << " '"
                                            << texture->getName() << "' image "
                                            << (first ? first->getFileName() : std::string("none")) << " "
                                            << (first ? first->s() : 0) << "x" << (first ? first->t() : 0)
                                            << " format 0x" << std::hex << (first ? first->getPixelFormat() : 0)
                                            << std::dec << " data " << (first && first->data() ? "yes" : "no")
                                            << " shadow " << source.mShadow;
                    }
                    if (dummy)
                        image = mResources.dummy(source.mShadow);
                    VkDescriptorImageInfo info{};
                    info.sampler = mResources.sampler(dummy ? nullptr : texture, source.mShadow);
                    info.imageView = image ? mResources.view(*image, source.mViewType) : VK_NULL_HANDLE;
                    info.imageLayout = image ? image->readLayout() : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    draw.mImages.push_back(info);
                }
            }

            VkDescriptorSet descriptorSet(DrawTemplate& draw, const std::array<VkBuffer, 3>& buffers)
            {
                for (const auto& [key, set] : draw.mSets)
                    if (key == buffers)
                        return set;
                Variant& variant = *draw.mVariant;
                VkDescriptorSet set = mContext.allocateSet(variant.mSetLayout);
                if (set == VK_NULL_HANDLE)
                    return set;
                VkWriteDescriptorSet writes[64];
                uint32_t count = 0;
                VkDescriptorBufferInfo bufferInfos[3]{};
                const uint32_t sizes[3] = { variant.mVertexUniforms.mSize, variant.mFragmentUniforms.mSize,
                    variant.mDrawStages != 0 ? sDrawBlockSize : 0u };
                for (uint32_t binding = 0; binding < 3; ++binding)
                {
                    if (sizes[binding] == 0)
                        continue;
                    bufferInfos[binding] = { buffers[binding], 0, sizes[binding] };
                    VkWriteDescriptorSet& write = writes[count++];
                    write = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
                    write.dstSet = set;
                    write.dstBinding = binding;
                    write.descriptorCount = 1;
                    write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
                    write.pBufferInfo = &bufferInfos[binding];
                }
                size_t image = 0;
                for (const SamplerSlot& slot : variant.mSamplers)
                {
                    if (count == 64)
                        break;
                    VkWriteDescriptorSet& write = writes[count++];
                    write = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
                    write.dstSet = set;
                    write.dstBinding = slot.mBinding;
                    write.descriptorCount = slot.mCount;
                    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                    write.pImageInfo = draw.mImages.data() + image;
                    image += slot.mCount;
                }
                vkUpdateDescriptorSets(mContext.vkDevice(), count, writes, 0, nullptr);
                draw.mSets.emplace_back(buffers, set);
                return set;
            }

            // Binds the program's descriptor set: the state's uniforms (sent once a frame, or once for each set of
            // volatile state sets) and the draw's matrices
            bool bindUniforms(DrawTemplate& draw, const osg::RefMatrix* modelView, const osg::RefMatrix* projection,
                uint32_t clipModes)
            {
                Variant& variant = *draw.mVariant;
                const VkDeviceSize alignment
                    = std::max<VkDeviceSize>(mContext.device().mProperties.limits.minUniformBufferOffsetAlignment, 16);
                std::array<VkBuffer, 3> buffers{};
                uint32_t offsets[3]{};
                uint32_t offsetCount = 0;

                VkBuffer stateBuffers[2]{};
                VkDeviceSize stateOffsets[2]{};
                if (draw.mVolatile.empty())
                {
                    for (int stage = 0; stage < 2; ++stage)
                    {
                        const std::vector<uint8_t>& bytes = draw.mBlocks[stage];
                        if (bytes.empty())
                            continue;
                        if (draw.mStatic[stage] == VK_NULL_HANDLE)
                        {
                            Span span = mResources.stream(bytes.size(), alignment);
                            if (span.mData == nullptr)
                                return false;
                            std::memcpy(span.mData, bytes.data(), bytes.size());
                            draw.mStatic[stage] = span.mBuffer;
                            draw.mStaticOffset[stage] = span.mOffset;
                        }
                        stateBuffers[stage] = draw.mStatic[stage];
                        stateOffsets[stage] = draw.mStaticOffset[stage];
                    }
                }
                else
                {
                    // the state sets the volatile uniforms come from for this draw
                    const osg::StateSet* sources[8]{};
                    uint64_t key = 0xcbf29ce484222325ull;
                    for (size_t i = 0; i < draw.mVolatile.size(); ++i)
                    {
                        const osg::StateSet* stateSet = chainStateSet(draw.mVolatile[i]->mLevel);
                        if (i < 8)
                            sources[i] = stateSet;
                        key = (key ^ reinterpret_cast<uintptr_t>(stateSet)) * 0x100000001b3ull;
                    }
                    const DrawTemplate::Copy* copy = nullptr;
                    for (const DrawTemplate::Copy& existing : draw.mCopies)
                        if (existing.mKey == key)
                            copy = &existing;
                    if (copy == nullptr)
                    {
                        DrawTemplate::Copy made{ key, {}, {} };
                        for (int stage = 0; stage < 2; ++stage)
                        {
                            const std::vector<uint8_t>& bytes = draw.mBlocks[stage];
                            if (bytes.empty())
                                continue;
                            Span span = mResources.stream(bytes.size(), alignment);
                            if (span.mData == nullptr)
                                return false;
                            std::memcpy(span.mData, bytes.data(), bytes.size());
                            for (size_t i = 0; i < draw.mVolatile.size(); ++i)
                            {
                                const DrawTemplate::Source& source = *draw.mVolatile[i];
                                if (source.mStage != stage)
                                    continue;
                                const osg::StateSet* stateSet
                                    = i < 8 ? sources[i] : chainStateSet(source.mLevel);
                                const osg::Uniform* uniform
                                    = stateSet ? stateSet->getUniform(source.mMember->mName) : nullptr;
                                if (uniform != nullptr)
                                    writeUniform(
                                        span.mData, static_cast<uint32_t>(bytes.size()), *source.mMember, *uniform);
                            }
                            made.mBuffer[stage] = span.mBuffer;
                            made.mOffset[stage] = span.mOffset;
                        }
                        draw.mCopies.push_back(made);
                        copy = &draw.mCopies.back();
                    }
                    for (int stage = 0; stage < 2; ++stage)
                    {
                        stateBuffers[stage] = copy->mBuffer[stage];
                        stateOffsets[stage] = copy->mOffset[stage];
                    }
                }
                for (int stage = 0; stage < 2; ++stage)
                    if (!draw.mBlocks[stage].empty())
                    {
                        buffers[stage] = stateBuffers[stage];
                        offsets[offsetCount++] = static_cast<uint32_t>(stateOffsets[stage]);
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
                    osg::Matrix rotation = modelViewMatrix;
                    rotation.setTrans(0.0, 0.0, 0.0);
                    osg::Matrix inverse;
                    inverse.invert(rotation);
                    float normal[12]{};
                    for (int c = 0; c < 3; ++c)
                        for (int r = 0; r < 3; ++r)
                            normal[c * 4 + r] = static_cast<float>(inverse(c, r));
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
                    buffers[2] = span.mBuffer;
                    offsets[offsetCount++] = static_cast<uint32_t>(span.mOffset);
                }

                VkDescriptorSet set = descriptorSet(draw, buffers);
                if (set == VK_NULL_HANDLE)
                    return false;
                vkCmdBindDescriptorSets(mContext.commands(), VK_PIPELINE_BIND_POINT_GRAPHICS, variant.mLayout, 0, 1,
                    &set, offsetCount, offsets);
                return true;
            }

            // The pipeline state of a leaf drawn into the current target
            PipelineState pipelineState(const Accumulated& state, VkPrimitiveTopology topology) const
            {
                PipelineState fixed = state.mFixed;
                fixed.mTopology = topology;
                fixed.mFlipY = mFlip ? 1 : 0;
                fixed.mFrontFace
                    = (state.mGlCounterClockwise != mFlip) ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE;
                fixed.mColorAttachments = (mTarget.mWindow || mTarget.mColor) ? 1 : 0;
                fixed.mColorFormat = mTarget.mColorFormat;
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
                std::vector<VkBuffer> mBuffers;
                std::vector<VkDeviceSize> mOffsets;
                std::vector<std::pair<const osg::Array*, unsigned>> mArrays; // what the inputs read, and when
                struct Primitive
                {
                    const osg::PrimitiveSet* mSet;
                    unsigned mModified;
                    VkPrimitiveTopology mTopology;
                    VkBuffer mIndexBuffer;
                    VkDeviceSize mIndexOffset;
                    VkIndexType mIndexType;
                    uint32_t mIndexCount; // converted indices' count
                    bool mConverted;
                };
                std::vector<Primitive> mPrimitives;
                struct Pipelines
                {
                    VkRenderPass mPass = VK_NULL_HANDLE;
                    PipelineState mFixed;
                    std::vector<VkPipeline> mPerPrimitive;
                };
                std::array<Pipelines, 4> mPipelines;
                unsigned mNextPipelines = 0;
            };

            // Which of the geometry's arrays an input reads
            static const osg::Array* inputArray(const osg::Geometry& geometry, const VertexInput& input,
                const osg::Program::AttribBindingList& bindings)
            {
                const std::string& name = input.mName;
                if (name.compare(0, 4, "osg_") == 0)
                {
                    if (name == "osg_Vertex")
                        return geometry.getVertexArray();
                    if (name == "osg_Normal")
                        return geometry.getNormalArray();
                    if (name == "osg_Color")
                        return geometry.getColorArray();
                    if (name == "osg_SecondaryColor")
                        return geometry.getSecondaryColorArray();
                    if (name == "osg_FogCoord")
                        return geometry.getFogCoordArray();
                    if (name.rfind("osg_MultiTexCoord", 0) == 0)
                        return geometry.getTexCoordArray(std::atoi(name.c_str() + 17));
                }
                auto found = bindings.find(name);
                return found != bindings.end() ? geometry.getVertexAttribArray(found->second) : nullptr;
            }

            // The buffer of a constant vertex input (OpenGL's current value: white for the color, (0, 0, 0, 1)
            // otherwise)
            bool constantInput(const VertexInput& input, VkFormat& format, VkBuffer& buffer, VkDeviceSize& offset)
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
                    pipelines.mPass = VK_NULL_HANDLE;
                for (const VertexInput& input : variant.mInputs)
                {
                    const osg::Array* array = inputArray(geometry, input, bindings);
                    entry.mArrays.emplace_back(array, array ? array->getModifiedCount() : 0u);
                    const uint32_t binding = static_cast<uint32_t>(entry.mLayout.mBindings.size());
                    VkFormat format = VK_FORMAT_UNDEFINED;
                    VkBuffer buffer = VK_NULL_HANDLE;
                    VkDeviceSize offset = 0;
                    uint32_t stride = 0;
                    if (array != nullptr && array->getBinding() != osg::Array::BIND_OFF
                        && array->getNumElements() > 0)
                    {
                        format = vertexFormat(*array, input.mInteger);
                        if (format != VK_FORMAT_UNDEFINED
                            && mResources.buffer(*array, buffer, offset, &entry.mStreamed))
                            stride = array->getBinding() == osg::Array::BIND_PER_VERTEX
                                    || array->getBinding() == osg::Array::BIND_UNDEFINED
                                ? array->getElementSize()
                                : 0;
                        else
                            format = VK_FORMAT_UNDEFINED;
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
                            out.mIndexBuffer = VK_NULL_HANDLE;
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
                            out.mIndexBuffer = VK_NULL_HANDLE;
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

            void forgetBindings()
            {
                mBoundPipeline = VK_NULL_HANDLE;
                mBoundVertices = nullptr;
                mBoundIndexBuffer = VK_NULL_HANDLE;
            }

            void drawGeometry(const osg::Geometry& geometry, Accumulated& state, const osg::RefMatrix* modelView,
                const osg::RefMatrix* projection, bool noDepthWrite = false)
            {
                derive(state, mFrameCounter);
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
                VkCommandBuffer commands = mContext.commands();
                GeometryEntry* entry = geometryEntry(geometry, *variant, state.mProgram->getAttribBindingList());
                if (entry == nullptr || entry->mPrimitives.empty())
                    return;

                // the pipelines for the state and pass (one for each primitive set's topology)
                PipelineState fixed = pipelineState(state, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                if (noDepthWrite)
                    fixed.mDepthWrite = 0;
                GeometryEntry::Pipelines* pipelines = nullptr;
                for (auto& candidate : entry->mPipelines)
                    if (candidate.mPass == mPass && std::memcmp(&candidate.mFixed, &fixed, sizeof(fixed)) == 0)
                        pipelines = &candidate;
                if (pipelines == nullptr)
                {
                    pipelines = &entry->mPipelines[entry->mNextPipelines++ % entry->mPipelines.size()];
                    pipelines->mPass = mPass;
                    pipelines->mFixed = fixed;
                    pipelines->mPerPrimitive.clear();
                    for (const GeometryEntry::Primitive& primitive : entry->mPrimitives)
                    {
                        PipelineState withTopology = fixed;
                        withTopology.mTopology = primitive.mTopology;
                        pipelines->mPerPrimitive.push_back(
                            mPrograms.pipeline(*variant, withTopology, entry->mLayout, mPass));
                    }
                }

                DrawTemplate* draw = templateFor(state, *variant);
                bool bound = false;
                for (size_t i = 0; i < entry->mPrimitives.size(); ++i)
                {
                    const GeometryEntry::Primitive& primitive = entry->mPrimitives[i];
                    const VkPipeline pipeline = pipelines->mPerPrimitive[i];
                    if (pipeline == VK_NULL_HANDLE)
                        continue;
                    const osg::PrimitiveSet& primitives = *primitive.mSet;
                    const bool indexed = primitive.mIndexBuffer != VK_NULL_HANDLE;
                    if (!indexed && primitives.getDrawElements() != nullptr)
                        continue;
                    if (!bound)
                    {
                        if (!bindUniforms(*draw, modelView, projection, state.mClipModes))
                            return;
                        if (mBoundVertices != entry || mBoundVerticesVersion != entry->mVersion)
                        {
                            if (!entry->mBuffers.empty())
                                vkCmdBindVertexBuffers(commands, 0, static_cast<uint32_t>(entry->mBuffers.size()),
                                    entry->mBuffers.data(), entry->mOffsets.data());
                            mBoundVertices = entry;
                            mBoundVerticesVersion = entry->mVersion;
                        }
                        setViewport(state.mMinDepth, state.mMaxDepth);
                        if (fixed.mStencil)
                            vkCmdSetStencilReference(commands, VK_STENCIL_FACE_FRONT_AND_BACK, state.mStencilReference);
                        if (fixed.mBlend)
                            vkCmdSetBlendConstants(commands, state.mBlendConstants);
                        bound = true;
                    }
                    if (pipeline != mBoundPipeline)
                    {
                        vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                        mBoundPipeline = pipeline;
                    }
                    const uint32_t instances = std::max(1u, static_cast<unsigned>(primitives.getNumInstances()));
                    if (indexed)
                    {
                        if (primitive.mIndexBuffer != mBoundIndexBuffer || primitive.mIndexOffset != mBoundIndexOffset
                            || primitive.mIndexType != mBoundIndexType)
                        {
                            vkCmdBindIndexBuffer(commands, primitive.mIndexBuffer, primitive.mIndexOffset,
                                primitive.mIndexType);
                            mBoundIndexBuffer = primitive.mIndexBuffer;
                            mBoundIndexOffset = primitive.mIndexOffset;
                            mBoundIndexType = primitive.mIndexType;
                        }
                        const uint32_t count = primitive.mConverted ? primitive.mIndexCount
                                                                    : primitives.getDrawElements()->getNumIndices();
                        vkCmdDrawIndexed(commands, count, instances, 0, 0, 0);
                    }
                    else if (primitives.getType() == osg::PrimitiveSet::DrawArrayLengthsPrimitiveType)
                    {
                        const auto& lengths = static_cast<const osg::DrawArrayLengths&>(primitives);
                        uint32_t first = static_cast<uint32_t>(lengths.getFirst());
                        for (GLsizei length : lengths)
                        {
                            vkCmdDraw(commands, static_cast<uint32_t>(length), instances, first, 0);
                            first += static_cast<uint32_t>(length);
                        }
                    }
                    else if (primitives.getType() == osg::PrimitiveSet::DrawArraysPrimitiveType)
                    {
                        const auto& arrays = static_cast<const osg::DrawArrays&>(primitives);
                        vkCmdDraw(commands, static_cast<uint32_t>(arrays.getCount()), instances,
                            static_cast<uint32_t>(arrays.getFirst()), 0);
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
                VkCommandBuffer commands = mContext.commands();
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
                    std::vector<VkBuffer> buffers;
                    std::vector<VkDeviceSize> offsets;
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
                    VkPipeline pipeline = mPrograms.pipeline(*variant, fixed, layout, mPass);
                    if (pipeline == VK_NULL_HANDLE)
                        continue;
                    DrawTemplate* draw = templateFor(*textured, *variant);
                    if (!bindUniforms(*draw, leaf._modelview.get(), leaf._projection.get(), textured->mClipModes))
                        return;
                    forgetBindings();
                    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                    if (!buffers.empty())
                        vkCmdBindVertexBuffers(
                            commands, 0, static_cast<uint32_t>(buffers.size()), buffers.data(), offsets.data());
                    setViewport(textured->mMinDepth, textured->mMaxDepth);
                    vkCmdDraw(commands, static_cast<uint32_t>(batch.mVertexCount), 1, 0, 0);
                    ++mDraws;
                }
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
                    AttachmentUse colorUse;
                    colorUse.mFormat = image->mFormat;
                    colorUse.mLoad = VK_ATTACHMENT_LOAD_OP_LOAD;
                    colorUse.mInitial = colorUse.mFinal = image->readLayout();
                    VkRenderPass pass = mContext.renderPass(colorUse, AttachmentUse{});
                    VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
                    begin.renderPass = pass;
                    begin.framebuffer
                        = mResources.framebuffer(pass, { image->mAttachmentView }, image->mWidth, image->mHeight);
                    begin.renderArea = { { 0, 0 }, { image->mWidth, image->mHeight } };
                    vkCmdBeginRenderPass(mContext.commands(), &begin, VK_SUBPASS_CONTENTS_INLINE);
                    forgetBindings();
                    mPass = pass;
                    mTarget = Target{};
                    mTarget.mWindow = false;
                    mTarget.mColor = image;
                    mTarget.mWidth = image->mWidth;
                    mTarget.mHeight = image->mHeight;
                    mTarget.mColorFormat = image->mFormat;
                    mArea = begin.renderArea;
                    mFlip = false;
                    mViewportDepth[0] = -1.f;
                    setViewport(0.f, 1.f);
                    vkCmdSetScissor(mContext.commands(), 0, 1, &mArea);
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
                    vkCmdEndRenderPass(mContext.commands());
                    mPass = VK_NULL_HANDLE;
                    map->mCompiled = map->mDrawables.size();
                    map->mDrawables.clear();
                }
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
                        Log(Debug::Error) << "Vulkan: the ripple shaders are missing";
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
                    AttachmentUse colorUse;
                    colorUse.mFormat = image->mFormat;
                    colorUse.mLoad = VK_ATTACHMENT_LOAD_OP_DONT_CARE; // every texel is written
                    colorUse.mInitial = colorUse.mFinal = image->readLayout();
                    VkRenderPass renderPass = mContext.renderPass(colorUse, AttachmentUse{});
                    VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
                    begin.renderPass = renderPass;
                    begin.framebuffer
                        = mResources.framebuffer(renderPass, { image->mAttachmentView }, image->mWidth, image->mHeight);
                    begin.renderArea = { { 0, 0 }, { image->mWidth, image->mHeight } };
                    vkCmdBeginRenderPass(mContext.commands(), &begin, VK_SUBPASS_CONTENTS_INLINE);
                    forgetBindings();
                    mPass = renderPass;
                    mTarget = Target{};
                    mTarget.mWindow = false;
                    mTarget.mColor = image;
                    mTarget.mWidth = image->mWidth;
                    mTarget.mHeight = image->mHeight;
                    mTarget.mColorFormat = image->mFormat;
                    mArea = begin.renderArea;
                    mFlip = false;
                    mViewportDepth[0] = -1.f;
                    setViewport(0.f, 1.f);
                    vkCmdSetScissor(mContext.commands(), 0, 1, &mArea);
                    drawGeometry(*alive, *step(base, mRipplePasses[pass].get()), identity.get(), identity.get());
                    vkCmdEndRenderPass(mContext.commands());
                    mPass = VK_NULL_HANDLE;
                }
            }

            void clearColor(Image& image, const VkClearColorValue& color)
            {
                VkCommandBuffer commands = mContext.commands();
                VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
                b.oldLayout = image.readLayout();
                b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                b.image = image.mImage;
                b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                const VkPipelineStageFlags shaders
                    = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                vkCmdPipelineBarrier(commands, shaders | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
                vkCmdClearColorImage(
                    commands, image.mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &b.subresourceRange);
                b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                b.newLayout = image.readLayout();
                b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    shaders | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
            }

            // The post-processing canvas: for now the scene's texture as it is
            void drawCanvas()
            {
                if (mSceneColor == nullptr)
                    return;
                Variant* variant = mPrograms.builtin("a3 blit", sBlitVertex, sBlitFragment);
                if (variant == nullptr || !variant->mOk)
                    return;
                PipelineState fixed;
                fixed.mFlipY = mFlip ? 1 : 0;
                fixed.mColorAttachments = (mTarget.mWindow || mTarget.mColor) ? 1 : 0;
                fixed.mColorFormat = mTarget.mColorFormat;
                fixed.mDepthFormat = mTarget.mDepthFormat;
                VkPipeline pipeline = mPrograms.pipeline(*variant, fixed, VertexLayout{}, mPass);
                if (pipeline == VK_NULL_HANDLE)
                    return;
                VkDescriptorSet set = mContext.allocateSet(variant->mSetLayout);
                if (set == VK_NULL_HANDLE || variant->mSamplers.empty())
                    return;
                VkDescriptorImageInfo image{ mResources.linearSampler(),
                    mResources.view(*mSceneColor, VK_IMAGE_VIEW_TYPE_2D), mSceneColor->readLayout() };
                VkWriteDescriptorSet write{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
                write.dstSet = set;
                write.dstBinding = variant->mSamplers.front().mBinding;
                write.descriptorCount = 1;
                write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                write.pImageInfo = &image;
                vkUpdateDescriptorSets(mContext.vkDevice(), 1, &write, 0, nullptr);
                VkCommandBuffer commands = mContext.commands();
                forgetBindings();
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(
                    commands, VK_PIPELINE_BIND_POINT_GRAPHICS, variant->mLayout, 0, 1, &set, 0, nullptr);
                setViewport(0.f, 1.f);
                vkCmdDraw(commands, 3, 1, 0, 0);
                ++mDraws;
            }

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
            VkQueryPool mGpuPools[sFramesInFlight]{};
            uint32_t mGpuUsed[sFramesInFlight]{};
            std::vector<std::string> mGpuLabels[sFramesInFlight];
            std::map<std::string, double> mGpuSums;
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
            std::unordered_map<StepKey, Step, StepHash> mSteps;
            struct GraphHash
            {
                size_t operator()(const std::pair<const osgUtil::StateGraph*, const osgUtil::RenderBin*>& key) const
                {
                    return std::hash<const void*>()(key.first) * 31 + std::hash<const void*>()(key.second);
                }
            };
            std::unordered_map<std::pair<const osgUtil::StateGraph*, const osgUtil::RenderBin*>, Accumulated*, GraphHash>
                mAccumulated;
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
            // what is bound in the command buffer (forgotten at each render pass and other binds)
            VkPipeline mBoundPipeline = VK_NULL_HANDLE;
            const GeometryEntry* mBoundVertices = nullptr;
            uint64_t mBoundVerticesVersion = 0;
            VkBuffer mBoundIndexBuffer = VK_NULL_HANDLE;
            VkDeviceSize mBoundIndexOffset = 0;
            VkIndexType mBoundIndexType = VK_INDEX_TYPE_UINT16;
            std::vector<std::shared_ptr<Accumulated>> mKeep;
            std::set<std::string> mUnknown;
            Image* mSceneColor = nullptr;
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
            VkRenderPass mPass = VK_NULL_HANDLE;
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
