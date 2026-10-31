// The experimental graphics' parts of OpenMW's object and terrain shaders (see experimental.cpp), added to their
// sources at anchors before they are translated. Plain text work with no OpenMW or OSG, so tools/check-shaders.sh runs it
// on the host too.
#include "experimentalshaders.hpp"

#include <cctype>
#include <locale>
#include <sstream>

namespace Alpha3
{
    namespace
    {
        // The declaration is compiled in: in no #if block, or the blocks around it are "#if 1" (the expanded source).
        // #ifdef/#ifndef count as on: the merged stages guard each uniform with one, so it is declared once.
        bool declared(const std::string& source, std::string_view decl)
        {
            const std::size_t at = source.find(decl);
            if (at == std::string::npos)
                return false;
            int depth = 0;
            bool inElse = false;
            std::size_t end = source.rfind('\n', at);
            while (end != std::string::npos && end > 0)
            {
                const std::size_t start = source.rfind('\n', end - 1);
                std::string_view line(source.data() + (start == std::string::npos ? 0 : start + 1),
                    end - (start == std::string::npos ? 0 : start + 1));
                while (!line.empty() && (line.front() == ' ' || line.front() == '\t'))
                    line.remove_prefix(1);
                while (!line.empty() && (line.back() == ' ' || line.back() == '\r'))
                    line.remove_suffix(1);
                if (line.starts_with("#endif"))
                    ++depth;
                else if (line.starts_with("#else") && depth == 0)
                    inElse = !inElse;
                else if (line.starts_with("#if"))
                {
                    if (depth == 0 && !line.starts_with("#ifdef") && !line.starts_with("#ifndef"))
                    {
                        const bool on = line == "#if 1";
                        if (on == inElse)
                            return false;
                        inElse = false;
                    }
                    else if (depth > 0)
                        --depth;
                }
                if (start == std::string::npos)
                    break;
                end = start;
            }
            return true;
        }

        class Injection
        {
        public:
            Injection(std::string& source, std::string_view shaderName, const MissingAnchor& missing)
                : mSource(source)
                , mShaderName(shaderName)
                , mMissing(missing)
            {
            }

            // inserts text after the anchor's line (after: true) or before it
            bool insert(std::string_view anchor, std::string_view text, bool after)
            {
                const std::size_t at = mSource.find(anchor);
                if (at == std::string::npos)
                    return missing(anchor);
                std::size_t where = at;
                if (after)
                {
                    where = mSource.find('\n', at + anchor.size());
                    where = where == std::string::npos ? mSource.size() : where + 1;
                }
                mSource.insert(where, std::string(text) + "\n");
                return true;
            }

            bool replace(std::string_view anchor, std::string_view text)
            {
                const std::size_t at = mSource.find(anchor);
                if (at == std::string::npos)
                    return missing(anchor);
                mSource.replace(at, anchor.size(), text);
                return true;
            }

        private:
            bool missing(std::string_view anchor)
            {
                if (mMissing)
                    mMissing(mShaderName, anchor);
                return false;
            }

            std::string& mSource;
            std::string mShaderName;
            const MissingAnchor& mMissing;
        };

        std::string fixed(float value)
        {
            std::ostringstream out;
            out.imbue(std::locale::classic());
            out.setf(std::ios::fixed);
            out.precision(4);
            out << value;
            return out.str();
        }

        std::string defines(const ExperimentalSettings& s)
        {
            std::string out = "\n// ---- Alpha3 experimental graphics\n";
            out += "#define A3_PROCEDURAL_STRENGTH " + fixed(s.mProceduralStrength) + "\n";
            out += "#define A3_PROCEDURAL_DISTANCE " + fixed(s.mProceduralDistance) + "\n";
            out += std::string("#define A3_REPLACE ") + (s.mReplace ? "true" : "false") + "\n";
            out += "#define A3_DETAIL_STRENGTH " + fixed(s.mDetailStrength) + "\n";
            out += "#define A3_DETAIL_DISTANCE " + fixed(s.mDetailDistance) + "\n";
            out += "#define A3_HEIGHT_SHARPNESS " + fixed(s.mHeightBlendSharpness) + "\n";
            out += "#define A3_TERRAIN_REPEAT 512.0\n";
            out += "#define A3_TREE_SWAY_STRENGTH " + fixed(s.mTreeSwayStrength) + "\n";
            out += "#define A3_TRUNK_SWAY " + fixed(s.mTrunkSway) + "\n";
            return out;
        }

        bool injectObjects(std::string& source, const std::string& name, const ExperimentalSettings& s,
            const ExperimentalShaderParts& parts, const MissingAnchor& missingAnchor)
        {
            if (!s.mProcedural && !s.mDetail)
                return false;
            Injection injection(source, name, missingAnchor);
            std::string library = defines(s);
            library += std::string("#define A3_DIFFUSE ") + (declared(source, "uniform sampler2D diffuseMap;") ? "1" : "0")
                + "\n";
            // only in a drawable's variant for its material (OSG's A3_MATERIAL state define)
            if (s.mProcedural)
                library += "#ifdef A3_MATERIAL\n" + std::string(parts.mProcedural) + "\n#endif\n";
            if (s.mDetail)
                library += std::string(parts.mDetail) + "\n";
            if (!injection.insert("void main()", library, false))
                return false;
            if (s.mDetail)
                injection.insert("    gl_FragData[0] = texture2D(diffuseMap, diffuseMapUV + offset);",
                    "    a3Detail(diffuseMap, diffuseMapUV + offset, passViewPos, A3_DETAIL_STRENGTH, "
                    "A3_DETAIL_DISTANCE, gl_FragData[0].xyz);",
                    true);
            if (s.mProcedural)
                injection.insert("    vec3 viewNormal = normalToView(normal);",
                    "#if A3_DIFFUSE && defined(A3_MATERIAL)\n"
                    "    a3Procedural(diffuseMap, normalMap, normalMapUV + offset, passViewPos, A3_PROCEDURAL_STRENGTH, "
                    "A3_PROCEDURAL_DISTANCE, A3_REPLACE, gl_FragData[0].xyz, normal);\n"
                    "#endif",
                    false);
            return true;
        }

        // The sway goes around the position: compiled in only where the drawable's state defines A3_TREE_SWAY
        bool injectObjectsVertex(std::string& source, const std::string& name, const ExperimentalSettings& s,
            const ExperimentalShaderParts& parts, const MissingAnchor& missingAnchor)
        {
            if (!s.mTreeSway)
                return false;
            Injection injection(source, name, missingAnchor);
            std::string library = defines(s);
            // objects.vert declares it itself for the particles' occlusion
            library += std::string("#define A3_HAS_VIEW_INVERSE ")
                + (declared(source, "uniform mat4 osg_ViewMatrixInverse;") ? "1" : "0") + "\n";
            library += std::string(parts.mTreeSway) + "\n";
            if (!injection.insert("void main(void)", library, false))
                return false;
            if (!injection.replace("    gl_Position = modelToClip(gl_Vertex);",
                    "    vec4 a3SwayedView = modelToView(gl_Vertex);\n"
                    "#ifdef A3_TREE_SWAY\n"
                    "    a3SwayedView = a3TreeSway(a3SwayedView);\n"
                    "#endif\n"
                    "    gl_Position = viewToClip(a3SwayedView);"))
                return true;
            injection.replace("    vec4 viewPos = modelToView(gl_Vertex);", "    vec4 viewPos = a3SwayedView;");
            return true;
        }

        bool injectTerrainVertex(std::string& source, const std::string& name, const ExperimentalSettings& s,
            const ExperimentalShaderParts& parts, const MissingAnchor& missingAnchor)
        {
            if (!s.mTriplanar)
                return false;
            Injection injection(source, name, missingAnchor);
            if (!injection.insert("void main(void)", "varying vec3 a3TerrainPos;", false))
                return false;
            injection.insert("    passNormal = gl_Normal.xyz;", "    a3TerrainPos = gl_Vertex.xyz;", true);
            return true;
        }

        bool injectTerrainFragment(std::string& source, const std::string& name, const ExperimentalSettings& s,
            const ExperimentalShaderParts& parts, const MissingAnchor& missingAnchor)
        {
            if (!s.mProcedural && !s.mDetail && !s.mTriplanar && !s.mHeightBlending)
                return false;
            Injection injection(source, name, missingAnchor);
            std::string library = defines(s);
            if (s.mTriplanar)
                library += "varying vec3 a3TerrainPos;\n";
            // only in a drawable's variant for its material (OSG's A3_MATERIAL state define)
            if (s.mProcedural)
                library += "#ifdef A3_MATERIAL\n" + std::string(parts.mProcedural) + "\n#endif\n";
            if (s.mDetail)
                library += std::string(parts.mDetail) + "\n";
            if (s.mTriplanar || s.mHeightBlending)
                library += std::string(parts.mTerrain) + "\n";
            if (!injection.insert("void main()", library, false))
                return false;
            if (s.mTriplanar)
                injection.insert("    vec4 diffuseTex = texture2D(diffuseMap, adjustedUV);",
                    "    diffuseTex = a3TriplanarColour(diffuseMap, diffuseTex, a3TerrainPos, passNormal);", true);
            if (s.mDetail)
                injection.insert("    gl_FragData[0] = vec4(diffuseTex.xyz, 1.0);",
                    "    a3Detail(diffuseMap, adjustedUV, passViewPos, A3_DETAIL_STRENGTH, A3_DETAIL_DISTANCE, "
                    "gl_FragData[0].xyz);",
                    true);
            if (s.mHeightBlending)
                injection.insert("    gl_FragData[0].a *= texture2D(blendMap, blendMapUV).a;",
                    "    gl_FragData[0].a = a3HeightBlend(gl_FragData[0].a, a3TerrainHeight(diffuseMap, adjustedUV), "
                    "A3_HEIGHT_SHARPNESS);",
                    true);
            if (s.mProcedural)
            {
                // on a steep slope the procedural surface (laid from above) gives way to the triplanar sides
                const std::string strength = s.mTriplanar
                    ? "A3_PROCEDURAL_STRENGTH * a3TriplanarWeights(normalize(passNormal)).z"
                    : "A3_PROCEDURAL_STRENGTH";
                // only where this pass's layer is painted (its blend weight, already in the alpha): a chunk draws a pass
                // for every layer, each over the whole chunk
                injection.insert("    vec3 viewNormal = normalToView(normal);",
                    "#ifdef A3_MATERIAL\n"
                    "    if (gl_FragData[0].a > 0.004)\n"
                    "        a3Procedural(diffuseMap, normalMap, adjustedUV, passViewPos, " + strength
                        + ", A3_PROCEDURAL_DISTANCE, A3_REPLACE, gl_FragData[0].xyz, normal);\n"
                    "#endif",
                    false);
            }
            if (s.mTriplanar)
                injection.replace("    vec3 viewNormal = normalToView(normal);",
                    "    vec3 viewNormal = normalize(gl_NormalMatrix * a3TriplanarNormal(normalMap, normal, a3TerrainPos, "
                    "passNormal));");
            return true;
        }

        bool endsWithName(const std::string& name, std::string_view file)
        {
            // "12 objects.frag", "objects.frag (merged)"
            const std::size_t at = name.find(file);
            if (at == std::string::npos)
                return false;
            const bool startOk = at == 0 || name[at - 1] == ' ' || name[at - 1] == '/';
            const std::size_t end = at + file.size();
            return startOk && (end == name.size() || name[end] == ' ');
        }
    }

    std::string_view defaultTreeSwayTextures()
    {
        // Morrowind's: tx_leaves_*, tx_bc_leaves_*, tx_mh_leaves_*, tx_branches_*, tx_bc_fern_*, tx_bm_pine_needles_*,
        // tx_bm_shrub_*, tx_hackle-lo_*, tx_ivy_*, tx_vine_*, tx_kelp_*, tx_willow_flower_*, tx_bc_moss*, tx_plant_*,
        // tx_caveplant*; not the dead leaves lying on the ground
        return "leaves,leaf,branches,fern,needles,shrub,hackle,ivy,vine,kelp,willow_flower,moss,plant_,caveplant,"
               "-deadleaves,-stem";
    }

    std::string_view defaultTrunkSwayTextures()
    {
        // tx_bark_*, tx_bc_bark_*, tx_ashtree_bark_*, tx_bm_bark_*, tx_mh_bark_*, tx_bm_treestump_01; not the Telvanni
        // towers' bark nor the driftwood's
        return "bark,trunk,-telv_,-deadbark";
    }

    bool treeSwayTexture(std::string_view path, std::string_view patterns, std::string_view defaults)
    {
        if (patterns.empty())
            patterns = defaults;
        const std::size_t slash = path.find_last_of("/\\");
        std::string name(slash == std::string_view::npos ? path : path.substr(slash + 1));
        for (char& c : name)
            c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        bool matched = false;
        std::size_t start = 0;
        while (start <= patterns.size())
        {
            std::size_t end = patterns.find(',', start);
            if (end == std::string_view::npos)
                end = patterns.size();
            std::string_view pattern = patterns.substr(start, end - start);
            while (!pattern.empty() && pattern.front() == ' ')
                pattern.remove_prefix(1);
            while (!pattern.empty() && pattern.back() == ' ')
                pattern.remove_suffix(1);
            const bool exclude = !pattern.empty() && pattern.front() == '-';
            if (exclude)
                pattern.remove_prefix(1);
            if (!pattern.empty())
            {
                std::string lower(pattern);
                for (char& c : lower)
                    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                if (name.find(lower) != std::string::npos)
                {
                    if (exclude)
                        return false;
                    matched = true;
                }
            }
            start = end + 1;
        }
        return matched;
    }

    bool treeSwayShader(std::string_view source)
    {
        return source.find("    gl_Position = modelToClip(gl_Vertex);") != std::string_view::npos
            && source.find("centroid varying vec4 passColor;") != std::string_view::npos;
    }

    bool proceduralShader(std::string_view source)
    {
        return source.find("    vec3 viewNormal = normalToView(normal);") != std::string_view::npos
            && source.find("void main()") != std::string_view::npos;
    }

    std::string experimentalShaderDefines(const ExperimentalSettings& settings)
    {
        return defines(settings);
    }

    bool injectExperimentalSource(const std::string& name, bool vertex, const ExperimentalSettings& settings,
        const ExperimentalShaderParts& parts, std::string& source, const MissingAnchor& missing)
    {
        if (vertex && endsWithName(name, "objects.vert"))
            return injectObjectsVertex(source, name, settings, parts, missing);
        if (!vertex && endsWithName(name, "objects.frag"))
            return injectObjects(source, name, settings, parts, missing);
        if (vertex && endsWithName(name, "terrain.vert"))
            return injectTerrainVertex(source, name, settings, parts, missing);
        if (!vertex && endsWithName(name, "terrain.frag"))
            return injectTerrainFragment(source, name, settings, parts, missing);
        return false;
    }
}
