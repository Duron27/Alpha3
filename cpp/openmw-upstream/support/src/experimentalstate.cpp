// The experimental graphics' state for single drawables, by the texture they are drawn with: their own variants of
// OpenMW's object and terrain shaders, switched by OSG state defines.
//
//  - Tree sway ([Experimental] tree sway, tree sway strength, tree sway textures, tree trunk sway, tree trunk textures):
//    A3_TREE_SWAY in objects.vert (experimental/treesway.glsl), 1 for leaves (leaves, branches, fronds, hanging moss;
//    not hair, not bark), 2 for a trunk, with the world height of its foot (from its bounds) so it bends from there.
//  - Procedural materials: A3_MATERIAL in objects.frag and terrain.frag (experimental/procedural.glsl), the material of
//    the texture by the material rules, so each variant has only that material's pattern in it.
//
// The shaders import the defines (the shaderDefinesComputed hook); as the cull adds a drawable (or one of a terrain
// chunk's passes, each with its layer's texture), the drawableState hook pushes the state set that defines them, so that
// drawable is drawn with its program's variant and everything else keeps the program it had. Works the same on the
// merged meshes of object paging, which keep their textures.
#include "support.hpp"

#include "generatedmaps.hpp"

#include <cmath>
#include <limits>
#include <map>
#include <mutex>
#include <tuple>
#include <unordered_map>

#include <osg/Shader>
#include <osg/StateSet>
#include <osg/Texture>
#include <osg/Uniform>
#include <osgUtil/CullVisitor>
#include <osgUtil/StateGraph>

namespace Alpha3
{
    namespace
    {
        enum class Sway
        {
            None,
            Leaves,
            Trunk,
        };

        struct TextureKind
        {
            Sway mSway = Sway::None;
            Material mMaterial = Material::None;
        };

        // The texture on unit 0 (the diffuse map) of the state the drawable is being added under: the nearest state set
        // up the state graph that has one
        const osg::Image* diffuseImage(osgUtil::CullVisitor& cv)
        {
            for (const osgUtil::StateGraph* graph = cv.getCurrentStateGraph(); graph != nullptr; graph = graph->_parent)
            {
                const osg::StateSet* stateset = graph->getStateSet();
                if (stateset == nullptr)
                    continue;
                const auto* texture
                    = static_cast<const osg::Texture*>(stateset->getTextureAttribute(0, osg::StateAttribute::TEXTURE));
                if (texture != nullptr)
                    return texture->getNumImages() > 0 ? texture->getImage(0) : nullptr;
            }
            return nullptr;
        }

        // The name the material rules go by: no directory, no extension, no _diffusespec, lower case
        std::string stemOf(const std::string& path)
        {
            std::string stem = path.substr(path.find_last_of("/\\") + 1);
            stem = stem.substr(0, stem.rfind('.'));
            constexpr std::string_view diffuseSpec = "_diffusespec";
            if (stem.size() > diffuseSpec.size()
                && stem.compare(stem.size() - diffuseSpec.size(), diffuseSpec.size(), diffuseSpec) == 0)
                stem.resize(stem.size() - diffuseSpec.size());
            for (char& c : stem)
                c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            return stem;
        }

        TextureKind kindOf(const osg::Image& image)
        {
            // per cull thread, so no lock; images live as long as the textures that hold them
            thread_local std::unordered_map<const osg::Image*, TextureKind> known;
            const auto found = known.find(&image);
            if (found != known.end())
                return found->second;
            const ExperimentalSettings& s = experimentalSettings();
            TextureKind kind;
            const std::string& file = image.getFileName();
            if (s.mTreeSway)
            {
                if (treeSwayTexture(file, s.mTreeSwayTextures, defaultTreeSwayTextures()))
                    kind.mSway = Sway::Leaves;
                else if (s.mTrunkSway > 0.f
                    && treeSwayTexture(file, s.mTrunkSwayTextures, defaultTrunkSwayTextures()))
                    kind.mSway = Sway::Trunk;
            }
            if (s.mProcedural)
            {
                static const std::vector<MaterialRule> rules
                    = parseMaterialRules(s.mRules.empty() ? defaultMaterialRules() : s.mRules);
                Material material = Material::None;
                if (materialOf(rules, stemOf(file), material))
                    kind.mMaterial = material;
            }
            known.emplace(&image, kind);
            return kind;
        }

        // The state set of a combination: shared by every drawable with it (a trunk's foot in steps of 16 units)
        const osg::StateSet* stateFor(Sway sway, int baseStep, Material material)
        {
            static std::mutex mutex;
            static std::map<std::tuple<int, int, int>, osg::ref_ptr<osg::StateSet>> states;
            std::lock_guard lock(mutex);
            osg::ref_ptr<osg::StateSet>& stateset
                = states[{ static_cast<int>(sway), baseStep, static_cast<int>(material) }];
            if (!stateset)
            {
                stateset = new osg::StateSet;
                if (sway != Sway::None)
                    stateset->setDefine(std::string(sTreeSwayDefine), sway == Sway::Leaves ? "1" : "2",
                        osg::StateAttribute::ON);
                if (sway == Sway::Trunk)
                    stateset->addUniform(new osg::Uniform("a3SwayBase", baseStep * 16.f));
                if (material != Material::None)
                    stateset->setDefine(std::string(sMaterialDefine), std::to_string(static_cast<int>(material)),
                        osg::StateAttribute::ON);
                stateset->setDataVariance(osg::Object::STATIC);
            }
            return stateset.get();
        }

        // The lowest world height of the drawable's bounds: its model view matrix, then back by the camera's view
        float worldBottom(osgUtil::CullVisitor& cv, const osg::Drawable& drawable)
        {
            thread_local const osg::Camera* camera = nullptr;
            thread_local unsigned traversal = ~0u;
            thread_local osg::Matrix inverseView;
            const osg::Camera* current = cv.getCurrentCamera();
            if (current != camera || cv.getTraversalNumber() != traversal)
            {
                camera = current;
                traversal = cv.getTraversalNumber();
                inverseView = current != nullptr ? current->getInverseViewMatrix() : osg::Matrix();
            }
            const osg::Matrix toWorld = *cv.getModelViewMatrix() * inverseView;
            const osg::BoundingBox& box = drawable.getBoundingBox();
            float bottom = std::numeric_limits<float>::max();
            for (unsigned corner = 0; corner < 8; ++corner)
                bottom = std::min(bottom, static_cast<float>((box.corner(corner) * toWorld).z()));
            return bottom;
        }
    }

    const osg::StateSet* experimentalDrawableState(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable)
    {
        const ExperimentalSettings& s = experimentalSettings();
        if (!s.mTreeSway && !s.mProcedural)
            return nullptr;
        auto& cv = static_cast<osgUtil::CullVisitor&>(cullVisitor);
        const osg::Image* image = diffuseImage(cv);
        if (image == nullptr)
            return nullptr;
        TextureKind kind = kindOf(*image);
        if (kind.mSway == Sway::Trunk && !drawable.getBoundingBox().valid())
            kind.mSway = Sway::None;
        if (kind.mSway == Sway::None && kind.mMaterial == Material::None)
            return nullptr;
        const int baseStep
            = kind.mSway == Sway::Trunk ? static_cast<int>(std::floor(worldBottom(cv, drawable) / 16.f)) : 0;
        return stateFor(kind.mSway, baseStep, kind.mMaterial);
    }

    void experimentalImportDefines(const osg::Shader& shader, osg::ShaderDefines& defines)
    {
        // the settings last: OSG makes shaders of its own before OpenMW has read them
        if (shader.getType() == osg::Shader::VERTEX && treeSwayShader(shader.getShaderSource())
            && experimentalSettings().mTreeSway)
            defines.insert(std::string(sTreeSwayDefine));
        if (shader.getType() == osg::Shader::FRAGMENT && proceduralShader(shader.getShaderSource())
            && experimentalSettings().mProcedural)
            defines.insert(std::string(sMaterialDefine));
    }
}
