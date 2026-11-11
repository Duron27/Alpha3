// Door portals ([Cells] seamless doors, OpenMW's mwworld/seamlessdoors.cpp): the other side of an open door, seen
// through its doorway: an interior through the exterior door that leads into it, or the exterior through an interior's
// door out. The doorway is a quad where the closed door was; the other side, moved so that its door sits on this one,
// is drawn only where that quad shows (the stencil), over what this side has behind it (the building's wall, the
// ground). In the scene camera's opaque pass, after this side's opaque objects (render bin 0) and before OpenMW's opaque
// copy (bin 9):
//   bin 3  the closed door's model at its depth, no colour: stencil 1 where it is seen (what stands in front of the
//          door hides it); the doorway is the door's own shape (an arch, a round top)
//   bin 4  the door again where the stencil is 1: the sky (black inside), and the depth of the far plane (the wall behind is gone)
//   bin 5  the other side, its own bins nested in this one, where the stencil is 1, lit as it is: an interior's light
//          (no sun shadows), or the weather's outside, fogged out to the horizon's colour where its objects stop
//   bin 7  the door again: the stencil back to 0
// With the camera in the doorway, walking through it, the passes draw a box going back from the doorway's quad (the
// door's bounds), over all: what the near plane cuts from the door, or all of it once the camera is past it, is still
// the other side.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <map>
#include <mutex>
#include <string>

#include <osg/BlendFunc>
#include <osg/ColorMask>
#include <osg/CullFace>
#include <osg/Geode>
#include <osg/Geometry>
#include <osg/MatrixTransform>
#include <osg/Program>
#include <osg/Stencil>
#include <osg/Texture2D>
#include <osgUtil/CullVisitor>

#include <components/misc/constants.hpp>
#include <components/resource/imagemanager.hpp>
#include <components/sceneutil/depth.hpp>
#include <components/sceneutil/shadow.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr const char* sVertex = "#version 120\n"
                                        "void main()\n"
                                        "{\n"
                                        "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
                                        "}\n";
        constexpr const char* sMaskFragment = "#version 120\n"
                                              "void main()\n"
                                              "{\n"
                                              "    gl_FragData[0] = vec4(0.0);\n"
                                              "}\n";
        // The sky: from the fog's colour at the horizon up to the sky's, and outside the weather's clouds (as OpenMW's
        // sky.frag colours them: the texture by the fog's colour lightened) on a plane overhead, fading out toward the
        // horizon as the sky's cloud dome does, scrolled as it scrolls them. The world's axes in view space give the
        // directions: the portal's geometry has no transform but the door's turn about the vertical
        constexpr const char* sClearVertex = "#version 120\n"
                                             "varying vec3 a3ViewPos;\n"
                                             "varying vec3 a3ViewX;\n"
                                             "varying vec3 a3ViewY;\n"
                                             "varying vec3 a3ViewUp;\n"
                                             "void main()\n"
                                             "{\n"
                                             "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
                                             "    a3ViewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;\n"
                                             "    a3ViewX = (gl_ModelViewMatrix * vec4(1.0, 0.0, 0.0, 0.0)).xyz;\n"
                                             "    a3ViewY = (gl_ModelViewMatrix * vec4(0.0, 1.0, 0.0, 0.0)).xyz;\n"
                                             "    a3ViewUp = (gl_ModelViewMatrix * vec4(0.0, 0.0, 1.0, 0.0)).xyz;\n"
                                             "}\n";
        constexpr const char* sClearFragment
            = "#version 120\n"
              "uniform float a3PortalFarDepth;\n"
              "uniform vec4 a3PortalFill;\n"
              "uniform vec4 a3PortalSky;\n"
              "uniform sampler2D a3PortalClouds;\n"
              "uniform sampler2D a3PortalNextClouds;\n"
              "uniform vec4 a3PortalCloudColor;\n"
              "uniform vec3 a3PortalCloudState;\n" // x: on, y: blend to the next, z: scroll
              "varying vec3 a3ViewPos;\n"
              "varying vec3 a3ViewX;\n"
              "varying vec3 a3ViewY;\n"
              "varying vec3 a3ViewUp;\n"
              "void main()\n"
              "{\n"
              "    vec3 dir = normalize(a3ViewPos);\n"
              "    float up = dot(dir, normalize(a3ViewUp));\n"
              "    vec3 sky = mix(a3PortalFill.rgb, a3PortalSky.rgb, smoothstep(0.0, 0.6, up));\n"
              "    if (a3PortalCloudState.x > 0.5 && up > 0.0)\n"
              "    {\n"
              "        vec2 across = vec2(dot(dir, normalize(a3ViewX)), dot(dir, normalize(a3ViewY)));\n"
              "        vec2 uv = across / (up + 0.15) * 0.25 + vec2(0.0, a3PortalCloudState.z);\n"
              "        vec4 clouds = mix(texture2D(a3PortalClouds, uv), texture2D(a3PortalNextClouds, uv), "
              "a3PortalCloudState.y);\n"
              "        float alpha = clouds.a * smoothstep(0.0, 0.25, up);\n"
              "        sky = mix(sky, clamp(clouds.rgb * a3PortalCloudColor.rgb, 0.0, 1.0), alpha);\n"
              "    }\n"
              "    gl_FragData[0] = vec4(sky, 1.0);\n"
              "    gl_FragDepth = a3PortalFarDepth;\n"
              "}\n";

        osg::ref_ptr<osg::Program> program(const char* vertex, const char* fragment, const char* name)
        {
            osg::ref_ptr<osg::Program> p = new osg::Program;
            osg::ref_ptr<osg::Shader> vs = new osg::Shader(osg::Shader::VERTEX, vertex);
            vs->setName(std::string(name) + ".vert");
            osg::ref_ptr<osg::Shader> fs = new osg::Shader(osg::Shader::FRAGMENT, fragment);
            fs->setName(std::string(name) + ".frag");
            p->addShader(vs);
            p->addShader(fs);
            return p;
        }

        osg::ref_ptr<osg::Geometry> makeGeometry(osg::ref_ptr<osg::Vec3Array> vertices, osg::ref_ptr<osg::PrimitiveSet> primitives)
        {
            osg::ref_ptr<osg::Geometry> geometry = new osg::Geometry;
            geometry->setVertexArray(vertices);
            geometry->addPrimitiveSet(primitives);
            geometry->setUseDisplayList(false);
            geometry->setUseVertexBufferObjects(true);
            geometry->setCullingActive(false);
            return geometry;
        }

        // the quad and the same quad depth behind it, and the four sides between them
        osg::ref_ptr<osg::Geometry> boxGeometry(const std::array<osg::Vec3f, 4>& quad, const osg::Vec3f& back)
        {
            osg::ref_ptr<osg::Vec3Array> vertices = new osg::Vec3Array;
            for (const osg::Vec3f& corner : quad)
                vertices->push_back(corner);
            for (const osg::Vec3f& corner : quad)
                vertices->push_back(corner + back);
            osg::ref_ptr<osg::DrawElementsUShort> triangles = new osg::DrawElementsUShort(GL_TRIANGLES);
            auto face = [&](unsigned short a, unsigned short b, unsigned short c, unsigned short d) {
                for (unsigned short i : { a, b, c, a, c, d })
                    triangles->push_back(i);
            };
            face(0, 1, 2, 3);
            face(4, 5, 6, 7);
            for (unsigned short i = 0; i < 4; ++i)
            {
                const unsigned short j = (i + 1) % 4;
                face(i, j, j + 4, i + 4);
            }
            return makeGeometry(vertices, triangles);
        }

        osg::ref_ptr<osg::Stencil> stencil(osg::Stencil::Function function, osg::Stencil::Operation pass)
        {
            osg::ref_ptr<osg::Stencil> s = new osg::Stencil;
            s->setFunction(function, 1, 0xff);
            s->setOperation(osg::Stencil::KEEP, osg::Stencil::KEEP, pass);
            s->setWriteMask(0xff);
            return s;
        }

        // The passes and the other side, for the scene camera alone, and only when the doorway is in view; with the
        // camera in the doorway, the box drawn over all in place of the quad (the root's first two children)
        class PortalCullCallback : public osg::NodeCallback
        {
        public:
            PortalCullCallback(const std::array<osg::Vec3f, 4>& quad, const osg::Vec3f& front, float depth)
                : mFront(front)
                , mDepth(depth)
            {
                for (const osg::Vec3f& corner : quad)
                    mDoorway.expandBy(corner);
                mDoorway.expandBy(osg::BoundingSphere(mDoorway.center() - front * depth, 0.f));
                mOrigin = quad[0];
                mAcross = quad[1] - quad[0];
                mWidth = mAcross.normalize();
                mUp = quad[3] - quad[0];
                mHeight = mUp.normalize();
            }

            void operator()(osg::Node* node, osg::NodeVisitor* nv) override
            {
                auto* cv = static_cast<osgUtil::CullVisitor*>(nv);
                const osg::Camera* camera = cv->getCurrentCamera();
                if (!camera || camera->getName() != Constants::SceneCamera)
                    return;
                if (cv->isCulled(mDoorway))
                    return;
                const osg::Vec3f eye = cv->getEyePoint() - mOrigin;
                const float across = eye * mAcross;
                const float up = eye * mUp;
                const float out = eye * mFront;
                const bool inDoorway = across > 0.f && across < mWidth && up > 0.f && up < mHeight && out < mDepth
                    && out > -mDepth;
                auto* group = static_cast<osg::Group*>(node);
                for (unsigned int i = 0; i < group->getNumChildren(); ++i)
                {
                    osg::Node* child = group->getChild(i);
                    const osg::UserDataContainer* tags = child->getUserDataContainer();
                    if (tags && !tags->getDescriptions().empty()
                        && (tags->getDescriptions().front() == "near") != inDoorway)
                        continue;
                    child->accept(*nv);
                }
            }

        private:
            osg::BoundingSphere mDoorway;
            osg::Vec3f mFront;
            float mDepth;
            osg::Vec3f mOrigin;
            osg::Vec3f mAcross;
            float mWidth = 0.f;
            osg::Vec3f mUp;
            float mHeight = 0.f;
        };

        // a weather's cloud texture, repeating; kept for the next door
        osg::ref_ptr<osg::Texture2D> cloudTexture(const std::string& path)
        {
            static std::map<std::string, osg::ref_ptr<osg::Texture2D>> textures;
            if (path.empty())
                return nullptr;
            auto found = textures.find(path);
            if (found != textures.end())
                return found->second;
            Resource::ImageManager* images = engineImageManager();
            if (!images)
                return nullptr;
            osg::ref_ptr<osg::Texture2D> texture = new osg::Texture2D(images->getImage(VFS::Path::Normalized(path)));
            texture->setWrap(osg::Texture::WRAP_S, osg::Texture::REPEAT);
            texture->setWrap(osg::Texture::WRAP_T, osg::Texture::REPEAT);
            textures.emplace(path, texture);
            return texture;
        }

        constexpr const char* sOtherName = "Alpha3 door portal: the other side";

        // The other side's sunlight comes from where it does there (turned as the other side is placed here), not from
        // where this side's does: the shaders take it in view space, so it is set for each camera that draws the portal
        class OtherSunCallback : public osg::NodeCallback
        {
        public:
            explicit OtherSunCallback(const osg::Matrixd& otherToWorld)
                : mOtherToWorld(otherToWorld)
            {
            }

            void setPosition(const osg::Vec4f& position)
            {
                std::lock_guard<std::mutex> lock(mMutex);
                mPosition = position;
            }

            void operator()(osg::Node* node, osg::NodeVisitor* nv) override
            {
                osg::Vec4f position;
                {
                    std::lock_guard<std::mutex> lock(mMutex);
                    position = mPosition;
                }
                osgUtil::CullVisitor* cv = nv->asCullVisitor();
                if (cv == nullptr || position == osg::Vec4f() || cv->getCurrentRenderStage() == nullptr
                    || cv->getCurrentRenderStage()->getInitialViewMatrix() == nullptr)
                {
                    traverse(node, nv);
                    return;
                }
                const osg::Vec3d here
                    = osg::Matrixd::transform3x3(osg::Vec3d(position.x(), position.y(), position.z()), mOtherToWorld);
                const osg::Vec4d inView
                    = osg::Vec4d(here, 0.0) * (*cv->getCurrentRenderStage()->getInitialViewMatrix());
                osg::ref_ptr<osg::StateSet> state = new osg::StateSet;
                state->addUniform(new osg::Uniform("sun.position", osg::Vec4f(inView)));
                cv->pushStateSet(state);
                traverse(node, nv);
                cv->popStateSet();
            }

        private:
            osg::Matrixd mOtherToWorld;
            std::mutex mMutex;
            osg::Vec4f mPosition;
        };

        void applyLight(osg::StateSet& other, osg::StateSet& clear, const DoorPortalLight& light)
        {
            other.getOrCreateUniform("sun.diffuse", osg::Uniform::FLOAT_VEC4)->set(light.mSun);
            other.getOrCreateUniform("sun.specular", osg::Uniform::FLOAT_VEC4)->set(light.mSun);
            other.getOrCreateUniform("sun.ambient", osg::Uniform::FLOAT_VEC4)->set(light.mAmbient);
            other.getOrCreateUniform("fog.color", osg::Uniform::FLOAT_VEC4)->set(light.mFill);
            other.getOrCreateUniform("fog.depth", osg::Uniform::FLOAT)->set(-1.f);
            other.getOrCreateUniform("fog.start", osg::Uniform::FLOAT)->set(light.mFog ? light.mFogStart : 1e6f);
            other.getOrCreateUniform("fog.end", osg::Uniform::FLOAT)->set(light.mFog ? light.mFogEnd : 2e6f);
            clear.getOrCreateUniform("a3PortalFill", osg::Uniform::FLOAT_VEC4)->set(light.mFill);
            clear.getOrCreateUniform("a3PortalSky", osg::Uniform::FLOAT_VEC4)->set(light.mSky);

            osg::ref_ptr<osg::Texture2D> clouds = cloudTexture(light.mClouds);
            osg::ref_ptr<osg::Texture2D> next = cloudTexture(light.mNextClouds);
            if (!next)
                next = clouds;
            const bool on = clouds != nullptr;
            const osg::StateAttribute::OverrideValue override = osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE;
            if (on)
            {
                // the doorway's far shape is the door's model: its own textures overridden
                clear.setTextureAttributeAndModes(0, clouds, override);
                clear.setTextureAttributeAndModes(1, next, override);
            }
            clear.getOrCreateUniform("a3PortalClouds", osg::Uniform::SAMPLER_2D)->set(0);
            clear.getOrCreateUniform("a3PortalNextClouds", osg::Uniform::SAMPLER_2D)->set(1);
            clear.getOrCreateUniform("a3PortalCloudColor", osg::Uniform::FLOAT_VEC4)
                ->set(light.mFill + osg::Vec4f(0.13f, 0.13f, 0.13f, 0.f));
            clear.getOrCreateUniform("a3PortalCloudState", osg::Uniform::FLOAT_VEC3)
                ->set(osg::Vec3f(on ? 1.f : 0.f, light.mNextClouds.empty() ? 0.f : light.mCloudBlend, light.mCloudOffset));
        }

        osg::StateSet* namedStateSet(osg::Group& portal, const char* name)
        {
            for (unsigned int i = 0; i < portal.getNumChildren(); ++i)
                if (portal.getChild(i)->getName() == name)
                    return portal.getChild(i)->getOrCreateStateSet();
            return nullptr;
        }
    }

    osg::ref_ptr<osg::Group> makeDoorPortal(const std::array<osg::Vec3f, 4>& quad, const osg::Vec3f& front, float depth,
        osg::ref_ptr<osg::Node> door, osg::ref_ptr<osg::Node> other, const osg::Matrixd& otherToWorld,
        const DoorPortalLight& light)
    {
        static const osg::ref_ptr<osg::Program> maskProgram = program(sVertex, sMaskFragment, "a3_doorportal_mask");
        static const osg::ref_ptr<osg::Program> clearProgram = program(sClearVertex, sClearFragment, "a3_doorportal_clear");
        const osg::StateAttribute::GLModeValue on = osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE;

        osg::ref_ptr<osg::Group> root = new osg::Group;
        root->setName("Alpha3 door portal");
        root->setCullCallback(new PortalCullCallback(quad, front, depth));
        root->setCullingActive(false);

        osg::ref_ptr<osg::Geometry> box = boxGeometry(quad, -front * depth);
        // each pass twice: the box over all with the camera in the doorway, else the door's own shape
        auto shape = [&](bool near) -> osg::ref_ptr<osg::Group> {
            osg::ref_ptr<osg::Group> group;
            if (near)
            {
                osg::ref_ptr<osg::Geode> geode = new osg::Geode;
                geode->addDrawable(box);
                group = geode;
            }
            else
            {
                group = new osg::Group;
                group->addChild(door);
            }
            group->addDescription(near ? "near" : "far");
            return group;
        };
        auto pass = [&](osg::StateSet& ss, int bin) {
            ss.setRenderBinDetails(bin, "RenderBin", osg::StateSet::OVERRIDE_PROTECTED_RENDERBIN_DETAILS);
            ss.setMode(GL_CULL_FACE, osg::StateAttribute::OFF | osg::StateAttribute::OVERRIDE);
            ss.setMode(GL_BLEND, osg::StateAttribute::OFF | osg::StateAttribute::OVERRIDE);
        };

        // bin 3: stencil 1 where the doorway is seen
        for (bool near : { true, false })
        {
            osg::ref_ptr<osg::Group> mask = shape(near);
            osg::StateSet* ss = mask->getOrCreateStateSet();
            pass(*ss, 3);
            ss->setAttributeAndModes(maskProgram, on);
            ss->setAttributeAndModes(new osg::ColorMask(false, false, false, false), on);
            ss->setAttributeAndModes(
                new SceneUtil::AutoDepth(near ? osg::Depth::ALWAYS : osg::Depth::LEQUAL, 0.0, 1.0, false), on);
            ss->setAttributeAndModes(stencil(osg::Stencil::ALWAYS, osg::Stencil::REPLACE), on);
            root->addChild(mask);
        }
        // bin 4: the doorway emptied, the sky (black inside) at the far plane
        osg::ref_ptr<osg::StateSet> clearState = new osg::StateSet;
        {
            pass(*clearState, 4);
            clearState->setAttributeAndModes(clearProgram, on);
            clearState->addUniform(new osg::Uniform("a3PortalFarDepth", SceneUtil::AutoDepth::isReversed() ? 0.f : 1.f));
            clearState->setAttributeAndModes(new SceneUtil::AutoDepth(osg::Depth::ALWAYS, 0.0, 1.0, true), on);
            clearState->setAttributeAndModes(stencil(osg::Stencil::EQUAL, osg::Stencil::KEEP), on);
            clearState->setDataVariance(osg::Object::DYNAMIC);
            for (bool near : { true, false })
            {
                osg::ref_ptr<osg::Group> clear = shape(near);
                clear->setStateSet(clearState);
                root->addChild(clear);
            }
        }
        // bin 5: the other side through it
        osg::ref_ptr<osg::MatrixTransform> place = new osg::MatrixTransform(otherToWorld);
        place->setName(sOtherName);
        {
            place->addChild(other);
            osg::StateSet* ss = place->getOrCreateStateSet();
            ss->setRenderBinDetails(5, "RenderBin");
            ss->setAttributeAndModes(stencil(osg::Stencil::EQUAL, osg::Stencil::KEEP), on);
            SceneUtil::ShadowManager::instance().disableShadowsForStateSet(*ss);
            root->addChild(place);
        }
        // its light set again as the weather changes
        place->getStateSet()->setDataVariance(osg::Object::DYNAMIC);
        applyLight(*place->getStateSet(), *clearState, light);
        osg::ref_ptr<OtherSunCallback> sun = new OtherSunCallback(otherToWorld);
        sun->setPosition(light.mSunPosition);
        place->setCullCallback(sun);
        root->setUserData(clearState);
        // bin 7: the stencil cleared
        osg::ref_ptr<osg::StateSet> resetState = new osg::StateSet;
        pass(*resetState, 7);
        resetState->setAttributeAndModes(maskProgram, on);
        resetState->setAttributeAndModes(new osg::ColorMask(false, false, false, false), on);
        resetState->setAttributeAndModes(new SceneUtil::AutoDepth(osg::Depth::ALWAYS, 0.0, 1.0, false), on);
        resetState->setAttributeAndModes(stencil(osg::Stencil::ALWAYS, osg::Stencil::ZERO), on);
        for (bool near : { true, false })
        {
            osg::ref_ptr<osg::Group> reset = shape(near);
            reset->setStateSet(resetState);
            root->addChild(reset);
        }
        return root;
    }

    void setDoorPortalLight(osg::Group& portal, const DoorPortalLight& light)
    {
        osg::StateSet* other = namedStateSet(portal, sOtherName);
        auto* clear = dynamic_cast<osg::StateSet*>(portal.getUserData());
        if (other && clear)
            applyLight(*other, *clear, light);
        for (unsigned int i = 0; i < portal.getNumChildren(); ++i)
            if (auto* sun = dynamic_cast<OtherSunCallback*>(portal.getChild(i)->getCullCallback()))
                sun->setPosition(light.mSunPosition);
    }
}
