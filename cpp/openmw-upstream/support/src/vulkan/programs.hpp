#ifndef ALPHA3_SUPPORT_VULKAN_PROGRAMS_H
#define ALPHA3_SUPPORT_VULKAN_PROGRAMS_H

// OSG's programs as Vulkan pipelines. A program's shaders are made as OSG makes them for OpenGL ES (the stage's shaders
// merged, the state's defines put in, the translation to "#version 320 es"), then compiled by glslang for Vulkan with
// its relaxed rules: the plain uniforms of each stage go into one uniform block (the vertex stage's at binding 0, the
// fragment stage's at 1), whose layout reflection gives; samplers get bindings from 2 on, and the stages' inputs and
// outputs locations, matched by name. The vertex shader ends by moving z from OpenGL's -1..1 to Vulkan's 0..1 and, for
// the window, turning y over (OpenGL's rows go up, so render targets are drawn the OpenGL way up and the window turned).
#include "vk.hpp"

#include <osg/StateSet>
#include <osg/observer_ptr>

#include <map>
#include <memory>
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
        float mBiasConstant = 0.f;
        float mBiasSlope = 0.f;
        uint32_t mBlend = 0;
        uint32_t mSrcColor = VK_BLEND_FACTOR_ONE, mDstColor = VK_BLEND_FACTOR_ZERO;
        uint32_t mSrcAlpha = VK_BLEND_FACTOR_ONE, mDstAlpha = VK_BLEND_FACTOR_ZERO;
        uint32_t mColorOp = VK_BLEND_OP_ADD, mAlphaOp = VK_BLEND_OP_ADD;
        uint32_t mWriteMask = 0xF;
        uint32_t mStencil = 0;
        uint32_t mStencilCompare = VK_COMPARE_OP_ALWAYS;
        uint32_t mStencilFail = VK_STENCIL_OP_KEEP, mStencilDepthFail = VK_STENCIL_OP_KEEP,
                 mStencilPass = VK_STENCIL_OP_KEEP;
        uint32_t mStencilReadMask = 0xFF, mStencilWriteMask = 0xFF;
        uint32_t mFlipY = 0;
        uint32_t mColorAttachments = 1;
        uint32_t mColorFormat = 0;
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
        // the light list's uniforms (OpenMW's LightBuffer and PointLightCount, point light shadows' slots), in a block of
        // their own (binding 3, the same in both stages): a draw with another light list sends only these
        UniformBlock mLightUniforms;
        VkShaderStageFlags mLightStages = 0;
        uint32_t mVaryingEnd = 0; // one past the highest varying location
        std::string mSources[2]; // the GLSL given to glslang, kept for a pipeline the driver refuses (dumped once)
    };

    class Programs
    {
    public:
        explicit Programs(Context& context);

        // The program made with these defines (compiled now if it is new; mOk false if it could not be)
        // clip: the variant that writes the clip distances (for draws with a clip plane on)
        Variant* variant(
            const osg::Program& program, const osg::StateSet::DefineList& defines, osg::State& state, bool clip);

        // A program of the translator's own from GLSL sources ("#version 320 es")
        Variant* builtin(const std::string& name, const std::string& vertex, const std::string& fragment);

        VkPipeline pipeline(Variant& variant, const PipelineState& state, const VertexLayout& layout, VkRenderPass pass);
        uint64_t pipelineCount() const { return mPipelineCount; }

    private:
        uint64_t mPipelineCount = 0;
        bool compile(
            Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip = false);

        Context& mContext;
        struct Entry
        {
            osg::observer_ptr<const osg::Program> mProgram;
            std::unique_ptr<Variant> mVariant;
        };
        std::map<std::pair<const osg::Program*, std::string>, Entry> mVariants;
        std::map<std::string, std::unique_ptr<Variant>> mBuiltins;
        std::vector<std::unique_ptr<Variant>> mRetired;
    };
}

#endif
