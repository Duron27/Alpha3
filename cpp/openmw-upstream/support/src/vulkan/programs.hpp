#ifndef ALPHA3_SUPPORT_VULKAN_PROGRAMS_H
#define ALPHA3_SUPPORT_VULKAN_PROGRAMS_H

// OSG's programs as Vulkan pipelines. A program's shaders are made as OSG makes them for OpenGL ES (the stage's shaders
// merged, the state's defines put in, the translation to "#version 320 es"), then compiled by glslang for Vulkan with
// its relaxed rules: the plain uniforms of each stage go into one uniform block (the vertex stage's at binding 0, the
// fragment stage's at 1), whose layout reflection gives; samplers get bindings from 2 on, and the stages' inputs and
// outputs locations, matched by name. The vertex shader ends by moving z from OpenGL's -1..1 to Vulkan's 0..1 and, for
// the window, turning y over (OpenGL's rows go up, so render targets are drawn the OpenGL way up and the window turned).
#include "vk.hpp"

#include <osg/Shader>
#include <osg/StateSet>
#include <osg/observer_ptr>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <cstdlib>
#include <string>
#include <unordered_map>
#include <vector>

#include "../glesdialect.hpp"

namespace osg
{
    class Program;
    class State;
}

namespace Alpha3::Vk
{
    class Context;

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
    };

    // A shader storage block (layout(std430, binding = N) buffer): set 1 at OpenGL's binding, which is the index of the
    // osg::ShaderStorageBufferBinding it reads (OpenMW's clustered lighting)
    struct StorageSlot
    {
        uint32_t mBinding = 0;
        VkShaderStageFlags mStages = 0;
        // a uniform block the program binds to an index (osg::Program::addBindUniformBlock: the post processor's
        // _data), read from the osg::UniformBufferBinding at that index; else a storage block
        bool mUniform = false;
        // a uniform block is at this binding plus its index, apart from the stages' own blocks (0 to 3)
        static constexpr uint32_t sUniformBlockBase = 16;
        // the index of the osg::BufferIndexBinding it reads
        uint32_t index() const { return mUniform ? mBinding - sUniformBlockBase : mBinding; }
    };

    struct VertexInput
    {
        std::string mName;
        uint32_t mLocation = 0;
        uint32_t mComponents = 4;
        bool mInteger = false;
        // which of a geometry's arrays it reads: an osg_ built in (Kind), else the program's attribute binding
        enum Kind : uint8_t
        {
            Generic,
            Vertex,
            Normal,
            Color,
            SecondaryColor,
            FogCoord,
            TexCoord,
            Unknown, // an osg_ name that is none of these: reads nothing
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

    // The fixed function state a pipeline is made with, besides its program and vertex layout
    struct PipelineState
    {
        uint32_t mTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        uint32_t mCullMode = VK_CULL_MODE_NONE;
        uint32_t mFrontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        uint32_t mDepthTest = 0;
        uint32_t mDepthWrite = 0;
        uint32_t mDepthCompare = VK_COMPARE_OP_LESS;
        uint32_t mDepthBias = 0;
        uint32_t mDepthClamp = 0; // GL_DEPTH_CLAMP (the shadow casters)
        float mBiasConstant = 0.f;
        float mBiasSlope = 0.f;
        uint32_t mBlend = 0;
        uint32_t mSrcColor = VK_BLEND_FACTOR_ONE, mDstColor = VK_BLEND_FACTOR_ZERO;
        uint32_t mSrcAlpha = VK_BLEND_FACTOR_ONE, mDstAlpha = VK_BLEND_FACTOR_ZERO;
        uint32_t mColorOp = VK_BLEND_OP_ADD, mAlphaOp = VK_BLEND_OP_ADD;
        uint32_t mWriteMask = 0xF;
        uint32_t mWriteMask1 = 0xF; // the second colour attachment's (osg::ColorMaski 1: the normals)
        uint32_t mStencil = 0;
        uint32_t mStencilCompare = VK_COMPARE_OP_ALWAYS;
        uint32_t mStencilFail = VK_STENCIL_OP_KEEP, mStencilDepthFail = VK_STENCIL_OP_KEEP,
                 mStencilPass = VK_STENCIL_OP_KEEP;
        uint32_t mStencilReadMask = 0xFF, mStencilWriteMask = 0xFF;
        uint32_t mFlipY = 0;
        uint32_t mClipZeroToOne = 0; // osg::ClipControl ZERO_TO_ONE (reverse z): clip z is not remapped from -1..1
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
        VkShaderModule mVertex = VK_NULL_HANDLE;
        VkShaderModule mFragment = VK_NULL_HANDLE;
        VkDescriptorSetLayout mSetLayout = VK_NULL_HANDLE;
        VkPipelineLayout mLayout = VK_NULL_HANDLE;
        UniformBlock mVertexUniforms;
        UniformBlock mFragmentUniforms;
        std::vector<SamplerSlot> mSamplers;
        std::vector<VertexInput> mInputs;
        std::map<std::string, UniformInitializer> mInitializers;
        std::unordered_map<std::string, VkPipeline> mPipelines;
        bool mUsesBuiltins = false;
        bool mClips = false; // writes the clip distances
        VkShaderStageFlags mDrawStages = 0; // the stages with the block of OSG's matrices (binding 2)
        // reads the model view matrix's inverse (OpenGL's gl_ModelViewMatrixInverse): the draw block's fifth matrix,
        // computed once a draw, instead of inverse() in the shader (which the Adreno 615's Vulkan driver ran for every
        // pixel: 30 ms a frame of the water at 2560x1600)
        bool mInverseModelView = false;
        // the light list's uniforms (OpenMW's LightBuffer and PointLightCount, point light shadows' slots), in a block of
        // their own (binding 3, the same in both stages): a draw with another light list sends only these
        UniformBlock mLightUniforms;
        VkShaderStageFlags mLightStages = 0;
        uint32_t mVaryingEnd = 0; // one past the highest varying location
        std::string mSources[2]; // the GLSL given to glslang, kept for a pipeline the driver refuses (dumped once)
        // Made away from the draw thread: the SPIR-V and the reflection above, then the Vulkan objects on the draw
        // thread (Programs::poll). Until then mOk is false and the variant's draws are left out.
        std::vector<unsigned> mSpirv[2];
        std::atomic<int> mStage{ 0 }; // 0 nothing pending, 1 compiling, 2 compiled (objects to make)
        bool mCompiled = false; // the SPIR-V was made (else the program cannot be compiled)
        bool mBuiltin = false; // the translator's own: its pipelines are made at once
        // the storage blocks the stages read (set 1, read only in these stages)
        std::vector<StorageSlot> mStorage;
        VkDescriptorSetLayout mStorageLayout = VK_NULL_HANDLE;
    };

    // A compute program (osg::DispatchCompute's): its uniforms in a block at set 0 binding 0, its storage blocks at set 1
    struct ComputeVariant
    {
        bool mOk = false;
        std::string mName;
        VkShaderModule mModule = VK_NULL_HANDLE;
        VkDescriptorSetLayout mSetLayout = VK_NULL_HANDLE;
        VkDescriptorSetLayout mStorageLayout = VK_NULL_HANDLE;
        VkPipelineLayout mLayout = VK_NULL_HANDLE;
        VkPipeline mPipeline = VK_NULL_HANDLE;
        UniformBlock mUniforms;
        std::vector<StorageSlot> mStorage;
    };

    class Programs
    {
    public:
        explicit Programs(Context& context);

        ~Programs();

        // The program made with these defines; mOk false if it could not be made, or (a new one) while it is compiled
        // on a thread of its own, unless wait: then it is compiled before this returns. Compiled programs are kept on
        // disk (the cache directory's vulkan/), by their shaders' sources, and loaded from there the next time.
        // clip: the variant that writes the clip distances (for draws with a clip plane on)
        Variant* variant(const osg::Program& program, const osg::StateSet::DefineList& defines, osg::State& state,
            bool clip, bool wait = false);

        // On the draw thread, each frame: the Vulkan objects of the programs compiled since (their draws come in)
        void poll();
        // Programs still being compiled
        size_t compiling() const { return mCompiling; }

        // The compute program of a program with a compute shader, compiled at once the first time (null when it has none,
        // mOk false when it could not be made)
        ComputeVariant* compute(const osg::Program& program);

        // A program of the translator's own from GLSL sources ("#version 320 es")
        Variant* builtin(const std::string& name, const std::string& vertex, const std::string& fragment);

        // The pipeline; a new one is made on a thread of its own (null until then: Programs::poll puts it in, and
        // pipelineGeneration changes), unless wait or the variant is a builtin
        VkPipeline pipeline(Variant& variant, const PipelineState& state, const VertexLayout& layout, VkRenderPass pass,
            bool wait = false);
        uint64_t pipelineCount() const { return mPipelineCount; }
        // Changes when poll has put in pipelines made on their thread
        uint64_t pipelineGeneration() const { return mPipelineGeneration; }
        // Once a second, whether a shader's source was changed in place (OpenMW's ShaderManager::setGlobalDefines,
        // when a post processing shader asks for the scene's normals): its programs are made again on their next use
        void checkSources();
        // goes up when one was: a variant looked up before is looked up again
        uint64_t sourceGeneration() const { return mSourceGeneration; }

    private:
        uint64_t mPipelineCount = 0;
        bool compile(
            Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip = false);
        // compile's part without the device's objects (any thread)
        bool compileSpirv(Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip,
            const std::map<std::string, uint32_t>& blockBindings = {});
        // and its part with them (the draw thread)
        bool createObjects(Variant& variant);

        // What the compiling thread does for a variant: the stages' sources as OpenMW made them, translated there
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
            std::map<std::string, uint32_t> mBlockBindings; // the program's uniform blocks' indices, by name
            std::map<std::string, uint32_t> mAttribBindings; // its attributes' locations (addBindAttribLocation)
            std::vector<osg::ref_ptr<const osg::Shader>> mStageShaders[2]; // the program's shaders, by stage
        };
        bool prepare(Job& job);
        void run(Job& job);
        VkPipeline createPipeline(Variant& variant, const PipelineState& state, const VertexLayout& layout, VkRenderPass pass);
        // the threads that compile programs and make pipelines
        void post(std::function<void()> task);
        void work();
        std::mutex mJobsMutex;
        std::condition_variable mJobsReady;
        std::deque<std::function<void()>> mJobs;
        std::vector<Variant*> mCompiled; // compiled, waiting for poll (under mJobsMutex)
        struct MadePipeline
        {
            Variant* mVariant;
            std::string mKey;
            VkPipeline mPipeline;
        };
        std::vector<MadePipeline> mMadePipelines; // likewise
        std::vector<std::thread> mWorkers;
        bool mStopping = false;
        size_t mCompiling = 0;
        uint64_t mPipelineGeneration = 0;
        std::string mSalt; // what the disk cache's keys begin with (cacheSalt)
        uint64_t mSavedPipelines = 0;
        std::chrono::steady_clock::time_point mLastSave{};

        Context& mContext;
        struct Entry
        {
            osg::observer_ptr<const osg::Program> mProgram;
            std::unique_ptr<Variant> mVariant;
            size_t mSourceHash = 0; // of its shaders' sources when it was made
            uint64_t mGeneration = 0; // the source generation it was last found current in
        };
        std::map<std::pair<const osg::Program*, std::string>, Entry> mVariants;
        // a program's shaders' sources hashed, each shader noted for checkSources
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
}

#endif
