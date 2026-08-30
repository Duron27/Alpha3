package org.openmw.utils

import org.openmw.Constants
import java.io.File

fun patchShadersLinking() {
    val vertex = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/core/vertex.h.glsl")
    var content = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/core/vertex.glsl").readText()
    if (!vertex.readText().contains("#pragma CONVERTED")) {
        content = content.replace("#version 120" ,"vec4 modelToView(vec4 pos);")
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        content = content.replace("lib/core/vertex.h.glsl" + '"', "lib/core/lighting_vertex_impl.glsl" + '"' + "\n#include " + '"' + "lib/material/struct.glsl" + '"' + "\n#include " + '"' + "lib/core/clip.glsl" + '"')
        vertex.writeText(content + "\n#pragma CONVERTED\n")
    }

    val fragment = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/core/fragment.h.glsl")
    if (!fragment.readText().contains("#pragma CONVERTED")) {
        content = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/core/fragment.glsl").readText()
        content = content.replace("#version 120" ,"")
        content = content.replace("lib/core/fragment.h.glsl" + '"', "lib/core/lighting_fragment_impl.glsl" + '"' + "\n#include " + '"' + "lib/material/struct.glsl" + '"')
        fragment.writeText(content + "\n#pragma CONVERTED\n")
    }

    val clip = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/core/clip.glsl")
    content = clip.readText()
    if (!clip.readText().contains("#pragma CONVERTED")) {
        content = content.replace("#version 330 core" ,"")
        clip.writeText(content + "\n#pragma CONVERTED\n")
    }

    val objectsFrag = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/objects.frag")
    content = objectsFrag.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        content = content.replace("uniform float near;" ,"//uniform float near;")
        objectsFrag.writeText(content + "\n#pragma CONVERTED\n")
    }

    val objectsVert = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/objects.vert")
    content = objectsVert.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        objectsVert.writeText(content + "\n#pragma CONVERTED\n")
    }

    val terrainFrag = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/terrain.frag")
    content = terrainFrag.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        content = content.replace("uniform float near;" ,"//uniform float near;")
        terrainFrag.writeText(content + "\n#pragma CONVERTED\n")
    }

    val groundcoverFrag = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/groundcover.frag")
    content = groundcoverFrag.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        content = content.replace("uniform float near;" ,"//uniform float near;")
        groundcoverFrag.writeText(content + "\n#pragma CONVERTED\n")
    }

    val groundcoverVert = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/groundcover.vert")
    content = groundcoverVert.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("#include " + '"' + "lib/light/clamp.glsl" + '"' + "\n#include", "#include")
        groundcoverVert.writeText(content + "\n#pragma CONVERTED\n")
    }

    val waterFrag = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/water.frag")
    content = waterFrag.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec2 screenRes;" ,"//uniform vec2 screenRes;")
        content = content.replace("uniform float near;" ,"//uniform float near;")
        content = content.replace("uniform DirectionalLight sun;" ,"//uniform DirectionalLight sun;")
        waterFrag.writeText(content + "\n#pragma CONVERTED\n")
    }

    val lightutil = File(Constants.USER_FILE_STORAGE + "/resources/shaders/lib/light/util.glsl")
    content = lightutil.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("* int(gridSize.z)) /", "* gridSize.z) /")
        lightutil.writeText(content + "\n#pragma CONVERTED\n")
    }

    val fog = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/fog.glsl")
    content = fog.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("#include", "//")
        content = content.replace("uniform DirectionalLight sun;", "")
        fog.writeText(content + "\n#pragma CONVERTED\n")
    }

    val skyFrag = File(Constants.USER_FILE_STORAGE + "/resources/shaders/compatibility/sky.frag")
    content = skyFrag.readText()
    if (!content.contains("#pragma CONVERTED")) {
        content = content.replace("uniform vec4 diffuseColor;", "uniform vec4 diffuseColor;\n\n#if @skyBlending\nuniform sampler2D sky;\nvec3 sampleSkyColor(vec2 uv)\n{\n    return texture2D(sky, uv).xyz;\n}\n#endif\n\n#include " + '"' + "lib/light/struct.glsl" + '"' + "\nuniform DirectionalLight sun;\n\n")
        skyFrag.writeText(content + "\n#pragma CONVERTED\n")
    }

}


private fun addLineToHeader(content: String, new_line: String): String {
    return content.replace("//HEADER_END\n", new_line + "\n//HEADER_END\n")
}

fun patchShadersToGLES() {

    File(Constants.USER_FILE_STORAGE + "/resources/shaders").walkTopDown().forEach {
        if (it.isFile()) {
            var content = File(it.toString()).readText()
            if (!content.contains("#pragma GLES")) {

                // comment out all extensions
                content = content.replace("#extension", "//#extension")

                // Replace version string
                content = content.replace("#version 120", "#version 320 es\n//HEADER_END\n")
                content = content.replace("#version 430 core", "#version 320 es\n//HEADER_END\n")
                content = content.replace("#version 440 core", "#version 320 es\n//HEADER_END\n")

                content = addLineToHeader(content, "#extension GL_EXT_clip_cull_distance : enable")

                content = addLineToHeader(content, "precision highp float;")
                content = addLineToHeader(content, "precision highp int;")
                content = addLineToHeader(content, "precision highp sampler2D;")
                content = addLineToHeader(content, "precision highp sampler3D;")
                content = addLineToHeader(content, "precision highp sampler2DShadow;")
                content = addLineToHeader(content, "precision highp sampler2DArray;")
                content = addLineToHeader(content, "precision highp image2D;")

                // replace frag output variables
                content = content.replace("gl_FragData[0]", "Color0")
                content = content.replace("gl_FragData[1]", "Color1")
                content = content.replace("gl_FragColor", "Color0")


                // replace FFP stuff with osg alternatives
                content = content.replace("gl_Vertex", "osg_Vertex")
                content = content.replace("gl_Normal", "osg_Normal")
                content = content.replace("gl_Color", "osg_Color")
                content = content.replace("gl_MultiTexCoord", "osg_MultiTexCoord")
                content = content.replace("gl_ModelViewProjectionMatrix", "osg_ModelViewProjectionMatrix")
                content = content.replace("gl_ModelViewMatrix", "osg_ModelViewMatrix")
                content = content.replace("gl_NormalMatrix", "osg_NormalMatrix")

                // Remove default values from uniforms (not supported on es)
                content = content.replace("uniform bool useAdvancedShader = false;", "uniform bool useAdvancedShader;")
                content = content.replace("uniform vec2 scaling = vec2(1.0, 1.0);", "uniform vec2 scaling;")
                content = content.replace("uniform bool useDiffuseMapForShadowAlpha = true;", "uniform bool useDiffuseMapForShadowAlpha;")
                content = content.replace("uniform bool alphaTestShadows = true;", "uniform bool alphaTestShadows;")

                content = content.replace("textureSize2D(diffuseMap, 0);", "vec2(256.0);")


                if (it.extension == "frag") {
                    // Add osg build-in uniforms
                    content = addLineToHeader(content, "uniform mat4 osg_ModelViewMatrix;")
                    content = addLineToHeader(content, "uniform mat3 osg_NormalMatrix;")

                    // slow gl_ModelViewMatrixInverse replacement
                    content = content.replace("osg_ModelViewMatrixInverse", "inverse(osg_ModelViewMatrix)")

                    // Add fragment output variables
                    content = addLineToHeader(content, "layout(location = 0) out vec4 Color0;")
                    content = addLineToHeader(content, "layout(location = 1) out vec4 Color1;")

                    // Add some defines
                    content = addLineToHeader(content, "#define texture2D texture")
                    content = addLineToHeader(content, "#define texture2DArray texture")
                    content = addLineToHeader(content, "#define textureSize2D textureSize")
                    content = addLineToHeader(content, "#define varying in")

                    // Add some shadows stuff
                    content = addLineToHeader(content, "#define shadow2DProj custom_shadow2DProj")
                    content = addLineToHeader(content, "vec4 custom_shadow2DProj(sampler2DShadow sampler, vec4 uv) { return vec4(textureProj(sampler, uv)); }")
                }
                else if (it.extension == "vert") {

                    if (it.name.contains("terrain_composite")) {
                        content = content.replace("osg_ModelViewMatrix * ", "")
                    }


                    if (it.name.contains("shadowcasting")) {
                        content = content.replace("    vec4 viewPos", "#if !@useDepthClamp\n    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n#endif\n    vec4 viewPos")
                    }


                    // Add osg build-in attributes/uniforms
                    content = addLineToHeader(content, "in vec4 osg_Vertex;")
                    content = addLineToHeader(content, "in vec3 osg_Normal;")
                    content = addLineToHeader(content, "in vec4 osg_Color;")
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord0;")
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord1;")
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord2;")
                    if (!it.name.contains("groundcover")) {
                        content = addLineToHeader(content, "in vec4 osg_MultiTexCoord3;")
                        content = addLineToHeader(content, "in vec4 osg_MultiTexCoord4;")
                    }
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord5;")
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord6;")
                    content = addLineToHeader(content, "in vec4 osg_MultiTexCoord7;")

                    content = addLineToHeader(content, "uniform mat4 osg_ModelViewProjectionMatrix;")
                    content = addLineToHeader(content, "uniform mat4 osg_ModelViewMatrix;")
                    content = addLineToHeader(content, "uniform mat3 osg_NormalMatrix;")

                    // Add some defines
                    content = addLineToHeader(content, "#define attribute in")
                    content = addLineToHeader(content, "#define varying out")

                }

                File(it.toString()).writeText(content + "\n#pragma GLES\n")
            }
        }
    }
}