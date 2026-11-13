#ifndef ALPHA3_SUPPORT_METAL_PROGRAMS_H
#define ALPHA3_SUPPORT_METAL_PROGRAMS_H

// OSG's programs as Metal pipelines (src/vulkan/programs.hpp is Android's, and this follows it). A program's shaders are
// made as OSG makes them for OpenGL ES (the stage's shaders merged, the state's defines put in, the translation to
// "#version 320 es"), compiled by glslang for Vulkan with its relaxed rules as the Vulkan renderer does (the same blocks
// and bindings: the stages' plain uniforms at bindings 0 and 1, OSG's matrices at 2, the lights at 3, samplers from 4,
// storage blocks in set 1), then turned into the Metal Shading Language by SPIRV-Cross, with each of those given a
// slot of Metal's argument tables (below). The vertex shader ends by moving z from OpenGL's -1..1 to 0..1 and, for
// render targets, turning y over: Metal's rows go down, so render targets are drawn the OpenGL way up (row 0 the
// bottom, as OpenGL's textures have it) and the window as it is.
#import <Metal/Metal.h>

#include "vkenums.hpp"

#include <osg/Shader>
#include <osg/StateSet>
#include <osg/observer_ptr>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "../glesdialect.hpp"

namespace osg
{
    class Program;
    class State;
}

namespace Alpha3::Mtl
{
    class Context;

    // Metal's buffer slots (the same in both stages)
    constexpr uint32_t sStageBlockSlot = 0; // the stage's own uniforms (Vulkan's binding 0 or 1)
    constexpr uint32_t sDrawBlockSlot = 1; // OSG's matrices (binding 2)
    constexpr uint32_t sLightBlockSlot = 2; // the light list's (binding 3)
    constexpr uint32_t sStorageSlotBase = 3; // the storage and program uniform blocks, in the variant's order
    constexpr uint32_t sMaxStorageSlots = 8;
    constexpr uint32_t sVertexBufferBase = 12; // the vertex inputs' buffers (up to 30)
    constexpr uint32_t sMaxVertexBuffers = 31 - sVertexBufferBase;

    struct UniformMember
    {
        std::string mName; // as OSG names the uniform: no [0] for an array
        uint32_t mOffset = 0;
        int mGlType = 0;
        uint32_t mArraySize = 1;
        uint32_t mArrayStride = 0;
    };

    struct UniformBlock
    {
        uint32_t mSize = 0; // 0: the stage has no uniforms
        std::vector<UniformMember> mMembers;
    };

    struct SamplerSlot
    {
        std::string mName;
        uint32_t mBinding = 0;
        uint32_t mCount = 1;
        VkImageViewType mViewType = VK_IMAGE_VIEW_TYPE_2D;
        bool mShadow = false;
        VkShaderStageFlags mStages = 0;
        // its first texture and sampler slot in each stage (vertex, fragment; -1 not read there): the slots of a stage
        // are given in the order of the variant's samplers, an array's elements one after the other
        int mSlot[2] = { -1, -1 };
    };

    // A shader storage block, or a uniform block the program binds to an index: as the Vulkan renderer's (set 1)
    struct StorageSlot
    {
        uint32_t mBinding = 0;
        VkShaderStageFlags mStages = 0;
        bool mUniform = false;
        static constexpr uint32_t sUniformBlockBase = 16;
        uint32_t index() const { return mUniform ? mBinding - sUniformBlockBase : mBinding; }
    };

    struct VertexInput
    {
        std::string mName;
        uint32_t mLocation = 0;
        uint32_t mComponents = 4;
        bool mInteger = false;
        enum Kind : uint8_t
        {
            Generic,
            Vertex,
            Normal,
            Color,
            SecondaryColor,
            FogCoord,
            TexCoord,
            Unknown,
        };
        Kind mKind = Generic;
        uint32_t mUnit = 0; // TexCoord's

        void classify()
        {
            mKind = Generic;
            if (mName.compare(0, 4, "osg_") != 0)
                return;
            mKind = Unknown;
            if (mName == "osg_Vertex")
                mKind = Vertex;
            else if (mName == "osg_Normal")
                mKind = Normal;
            else if (mName == "osg_Color")
                mKind = Color;
            else if (mName == "osg_SecondaryColor")
                mKind = SecondaryColor;
            else if (mName == "osg_FogCoord")
                mKind = FogCoord;
            else if (mName.rfind("osg_MultiTexCoord", 0) == 0)
            {
                mKind = TexCoord;
                mUnit = static_cast<uint32_t>(std::atoi(mName.c_str() + 17));
            }
        }
    };

    // The fixed function state of a draw, besides its program and vertex layout, in Vulkan's terms (the Vulkan
    // renderer's): part of it is the pipeline's (blend, formats, the vertex stage's constants), the rest the encoder's
    struct PipelineState
    {
        uint32_t mTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        uint32_t mCullMode = VK_CULL_MODE_NONE;
        uint32_t mFrontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        uint32_t mDepthTest = 0;
        uint32_t mDepthWrite = 0;
        uint32_t mDepthCompare = VK_COMPARE_OP_LESS;
        uint32_t mDepthBias = 0;
        uint32_t mDepthClamp = 0;
        float mBiasConstant = 0.f;
        float mBiasSlope = 0.f;
        uint32_t mBlend = 0;
        uint32_t mSrcColor = VK_BLEND_FACTOR_ONE, mDstColor = VK_BLEND_FACTOR_ZERO;
        uint32_t mSrcAlpha = VK_BLEND_FACTOR_ONE, mDstAlpha = VK_BLEND_FACTOR_ZERO;
        uint32_t mColorOp = VK_BLEND_OP_ADD, mAlphaOp = VK_BLEND_OP_ADD;
        uint32_t mWriteMask = 0xF;
        uint32_t mWriteMask1 = 0xF;
        uint32_t mStencil = 0;
        uint32_t mStencilCompare = VK_COMPARE_OP_ALWAYS;
        uint32_t mStencilFail = VK_STENCIL_OP_KEEP, mStencilDepthFail = VK_STENCIL_OP_KEEP,
                 mStencilPass = VK_STENCIL_OP_KEEP;
        uint32_t mStencilReadMask = 0xFF, mStencilWriteMask = 0xFF;
        uint32_t mFlipY = 0;
        uint32_t mClipZeroToOne = 0;
        uint32_t mColorAttachments = 1;
        uint32_t mColorFormat = 0;
        uint32_t mColorFormat1 = 0;
        uint32_t mDepthFormat = 0;
    };

    struct VertexLayout
    {
        std::vector<VkVertexInputBindingDescription> mBindings;
        std::vector<VkVertexInputAttributeDescription> mAttributes;
    };

    struct Variant
    {
        bool mOk = false;
        std::string mName;
        id<MTLLibrary> mLibrary[2] = { nil, nil };
        std::string mEntry[2]; // the stages' functions in their libraries
        id<MTLFunction> mFragment = nil;
        id<MTLFunction> mVertex[4] = { nil, nil, nil, nil }; // by the constants: flip y (1), half z (2)
        std::mutex mFunctionsMutex; // the vertex functions are made on the pipelines' threads
        UniformBlock mVertexUniforms;
        UniformBlock mFragmentUniforms;
        std::vector<SamplerSlot> mSamplers;
        std::vector<VertexInput> mInputs;
        std::map<std::string, UniformInitializer> mInitializers;
        std::unordered_map<std::string, id<MTLRenderPipelineState>> mPipelines;
        bool mUsesBuiltins = false;
        bool mClips = false;
        VkShaderStageFlags mDrawStages = 0;
        bool mInverseModelView = false;
        UniformBlock mLightUniforms;
        VkShaderStageFlags mLightStages = 0;
        uint32_t mVaryingEnd = 0;
        std::string mSources[2]; // the GLSL given to glslang, kept for a pipeline Metal refuses (dumped once)
        std::string mMsl[2]; // the stages in the Metal Shading Language (what the disk cache keeps)
        std::atomic<int> mStage{ 0 }; // 0 nothing pending, 1 compiling, 2 compiled
        bool mCompiled = false;
        bool mBuiltin = false;
        std::vector<StorageSlot> mStorage;
    };

    // A compute program (osg::DispatchCompute's): its uniforms in a block at buffer 0, its storage blocks after
    struct ComputeVariant
    {
        bool mOk = false;
        std::string mName;
        id<MTLComputePipelineState> mPipeline = nil;
        UniformBlock mUniforms;
        std::vector<StorageSlot> mStorage;
        MTLSize mGroup = MTLSizeMake(1, 1, 1); // the shader's local size
    };

    class Programs
    {
    public:
        explicit Programs(Context& context);
        ~Programs();

        // As the Vulkan renderer's: the program made with these defines; mOk false if it could not be made, or (a new
        // one) while it is compiled on a thread of its own, unless wait. Compiled programs are kept on disk (the cache
        // directory's metal/), by their shaders' sources.
        Variant* variant(const osg::Program& program, const osg::StateSet::DefineList& defines, osg::State& state,
            bool clip, bool wait = false);

        // On the draw thread, each frame: the programs compiled since come in
        void poll();
        size_t compiling() const { return mCompiling; }

        ComputeVariant* compute(const osg::Program& program);

        // A program of the translator's own from GLSL sources ("#version 320 es")
        Variant* builtin(const std::string& name, const std::string& vertex, const std::string& fragment);

        // The render pipeline; a new one is made on a thread of its own (nil until then: poll puts it in, and
        // pipelineGeneration changes), unless wait or the variant is a builtin
        id<MTLRenderPipelineState> pipeline(
            Variant& variant, const PipelineState& state, const VertexLayout& layout, bool wait = false);
        uint64_t pipelineCount() const { return mPipelineCount; }
        uint64_t pipelineGeneration() const { return mPipelineGeneration; }
        void checkSources();
        uint64_t sourceGeneration() const { return mSourceGeneration; }

    private:
        uint64_t mPipelineCount = 0;
        bool compile(
            Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip = false);
        // the compile (any thread): SPIR-V, then MSL and its libraries
        bool compileStages(Variant& variant, std::string vertex, std::string fragment, const std::string& name,
            bool clip, const std::map<std::string, uint32_t>& blockBindings = {});
        // the libraries of the variant's MSL (any thread)
        bool createObjects(Variant& variant);

        struct Job
        {
            Variant* mVariant = nullptr;
            std::string mSources[2];
            std::string mDefines[2];
            osg::ref_ptr<const osg::Shader> mShaders[2];
            std::string mName;
            std::string mCacheKey;
            bool mClip = false;
            osg::State* mState = nullptr;
            std::string mProgramName;
            std::string mSalt;
            std::map<std::string, uint32_t> mBlockBindings;
            std::map<std::string, uint32_t> mAttribBindings;
            std::vector<osg::ref_ptr<const osg::Shader>> mStageShaders[2];
        };
        bool prepare(Job& job);
        void run(Job& job);
        id<MTLRenderPipelineState> createPipeline(Variant& variant, const PipelineState& state, const VertexLayout& layout);
        id<MTLFunction> vertexFunction(Variant& variant, bool flipY, bool halfZ);
        void post(std::function<void()> task);
        void work();
        std::mutex mJobsMutex;
        std::condition_variable mJobsReady;
        std::deque<std::function<void()>> mJobs;
        std::vector<Variant*> mCompiled;
        struct MadePipeline
        {
            Variant* mVariant;
            std::string mKey;
            id<MTLRenderPipelineState> mPipeline;
        };
        std::vector<MadePipeline> mMadePipelines;
        std::vector<std::thread> mWorkers;
        bool mStopping = false;
        size_t mCompiling = 0;
        uint64_t mPipelineGeneration = 0;
        std::string mSalt;

        Context& mContext;
        struct Entry
        {
            osg::observer_ptr<const osg::Program> mProgram;
            std::unique_ptr<Variant> mVariant;
            size_t mSourceHash = 0;
            uint64_t mGeneration = 0;
        };
        std::map<std::pair<const osg::Program*, std::string>, Entry> mVariants;
        size_t sourceHash(const osg::Program& program);
        std::map<const osg::Shader*, std::pair<osg::observer_ptr<const osg::Shader>, size_t>> mShaderHashes;
        uint64_t mSourceGeneration = 0;
        uint32_t mSourceChecks = 0;
        std::map<std::string, std::unique_ptr<Variant>> mBuiltins;
        struct ComputeEntry
        {
            osg::observer_ptr<const osg::Program> mProgram;
            std::unique_ptr<ComputeVariant> mVariant;
        };
        std::map<const osg::Program*, ComputeEntry> mComputes;
        bool makeCompute(ComputeVariant& variant, std::string source);
        std::vector<std::unique_ptr<Variant>> mRetired;
    };

    // Metal's enums for Vulkan's (PipelineState's)
    MTLCompareFunction mtlCompare(uint32_t op);
    MTLStencilOperation mtlStencilOp(uint32_t op);
}

#endif
