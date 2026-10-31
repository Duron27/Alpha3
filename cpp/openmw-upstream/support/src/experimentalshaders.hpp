#ifndef ALPHA3_SUPPORT_EXPERIMENTALSHADERS_H
#define ALPHA3_SUPPORT_EXPERIMENTALSHADERS_H

// The experimental graphics' switches ([Experimental], read by experimental.cpp) and the shader injection
// (experimentalshaders.cpp). No OSG or OpenMW, for the host tools.

#include <functional>
#include <string>
#include <string_view>

namespace Alpha3
{
    struct ExperimentalSettings
    {
        bool mNormalMaps = false; // generated normal maps
        bool mParallax = false;
        float mNormalStrength = 1.f;
        bool mSpecular = false; // generated specular maps
        std::string mRules; // material rules, empty: the defaults
        bool mProcedural = false;
        bool mReplace = true; // the texture's painted detail not used
        float mProceduralStrength = 1.f;
        float mProceduralDistance = 3000.f;
        bool mDetail = false;
        float mDetailStrength = 1.f;
        float mDetailDistance = 1500.f;
        bool mTriplanar = false;
        bool mHeightBlending = false;
        float mHeightBlendSharpness = 2.5f;
        bool mAmbientOcclusion = false;
        bool mLook = false;
        bool mGrass = false;
        float mGrassDensity = 1.f;
        float mGrassHeight = 1.f;
        float mGrassDistance = 2500.f;
        float mGrassWind = 1.f;
        bool mTreeSway = false;
        float mTreeSwayStrength = 1.f;
        std::string mTreeSwayTextures; // what leafy textures' names contain, comma separated; empty: the defaults
        float mTrunkSway = 0.5f; // the trunks' bend, 0: they stay still
        std::string mTrunkSwayTextures; // what the trunks' textures' names contain; empty: the defaults
    };

    // The textures [Experimental] tree sway moves by default: leaves, branches, fronds, hanging moss; and the trunks'
    std::string_view defaultTreeSwayTextures();
    std::string_view defaultTrunkSwayTextures();
    // Whether a texture (its path or name) is one of those, by a comma separated list: parts of the name, and parts that
    // keep it out with a "-" in front (the defaults when the list is empty)
    bool treeSwayTexture(std::string_view path, std::string_view patterns, std::string_view defaults);
    // OSG's define that switches the sway on in a drawable's own variant of the objects' vertex shader: "1" leaves, "2" a
    // trunk (bending from a3SwayBase, the world height of its foot)
    constexpr std::string_view sTreeSwayDefine = "A3_TREE_SWAY";
    // Whether a vertex shader (its expanded source) is OpenMW's objects.vert, the one the sway goes into
    bool treeSwayShader(std::string_view source);
    // OSG's define that picks the procedural material in a drawable's variant of objects.frag or terrain.frag
    constexpr std::string_view sMaterialDefine = "A3_MATERIAL";
    // Whether a fragment shader (its expanded source) is objects.frag or terrain.frag, the ones procedural goes into
    bool proceduralShader(std::string_view source);

    // The GLSL libraries that are added (src/experimental/*.glsl)
    struct ExperimentalShaderParts
    {
        std::string_view mProcedural, mDetail, mTerrain, mTreeSway;
    };

    // Called with the shader's name and an anchor that was not found (that part is left out)
    using MissingAnchor = std::function<void(std::string_view shader, std::string_view anchor)>;

    // Adds the switched-on parts to OpenMW's objects.vert, objects.frag, terrain.vert or terrain.frag (by the name OSG gives the
    // shader: "12 objects.frag", "objects.frag (merged)"), expanded but not yet translated. False: not one of them, or
    // nothing to add.
    bool injectExperimentalSource(const std::string& name, bool vertex, const ExperimentalSettings& settings,
        const ExperimentalShaderParts& parts, std::string& source, const MissingAnchor& missing);

    // The #defines the parts are given (their settings), for the program binary cache's key
    std::string experimentalShaderDefines(const ExperimentalSettings& settings);
}

#endif
