#include "glesdialect.hpp"

#include "shaderconverter.hpp"

#include <array>
#include <atomic>
#include <cctype>
#include <cstddef>
#include <cstdlib>
#include <regex>
#include <set>
#include <string_view>
#include <utility>

namespace Alpha3
{
    namespace
    {
        bool isIdentStart(char c)
        {
            return std::isalpha(static_cast<unsigned char>(c)) || c == '_';
        }

        bool isIdentChar(char c)
        {
            return std::isalnum(static_cast<unsigned char>(c)) || c == '_';
        }

        // What the translated source needs declared on top
        struct Needs
        {
            std::set<std::string> attributes; // osg_Vertex, osg_MultiTexCoord0, ...
            std::set<int> fragDataOutputs;
            bool modelView = false;
            bool projection = false;
            bool modelViewProjection = false;
            bool normalMatrix = false;
            bool inverse = false; // omw_inverse (desktop 1.20 has no inverse())
            bool shadow2D = false;
            bool shadow2DProj = false;
            bool textureSize2D = false; // omw_textureSize2D (EXT_gpu_shader4 name, returns a vec2)
            bool clipVertexSink = false;
            bool clipPlanes = false; // omw_applyClipPlanes in place of the gl_ClipVertex assignment
        };

        std::atomic<bool> sClipDistancesAvailable{ false };

        // OSG gives the clip planes 0 and 1 (all OpenMW uses) as eye-space uniforms; a plane that is off reads (0,0,0,1)
        constexpr std::string_view sClipPlanesHelper
            = "uniform vec4 osg_ClipPlane0; uniform vec4 osg_ClipPlane1;"
              " void omw_applyClipPlanes(vec4 v) { gl_ClipDistance[0] = dot(osg_ClipPlane0, v);"
              " gl_ClipDistance[1] = dot(osg_ClipPlane1, v); }";

        // OpenGL ES has no GL_DEPTH_CLAMP (Adreno has no GL_EXT_depth_clamp either), which the shadow casters are drawn
        // with: z is clamped to the near plane after the shader's main, whatever way it returns, so casters in front of
        // the shadow camera keep their shadow.
        std::string wrapMainForDepthClamp(const std::string& source)
        {
            static const std::regex mainFunction(R"(\bvoid\s+main\s*\(\s*(void)?\s*\))");
            std::smatch match;
            if (!std::regex_search(source, match, mainFunction))
                return source;
            return std::string(match.prefix()) + "void omw_main()" + std::string(match.suffix())
                + "\nvoid main()\n{\n    omw_main();\n    gl_Position.z = max(gl_Position.z, -gl_Position.w);\n}\n";
        }

        // "gl_ClipVertex = <eye position>;" becomes a call that writes the clip distances
        std::string replaceClipVertex(const std::string& source, Needs& needs)
        {
            static const std::regex assignment(R"(\bgl_ClipVertex\s*=\s*([^;]+);)");
            std::string replaced = std::regex_replace(source, assignment, "omw_applyClipPlanes($1);");
            if (replaced != source)
                needs.clipPlanes = true;
            return replaced;
        }

        // GLSL 1.20 texture functions that became overloads of texture() and friends
        constexpr std::array<std::pair<std::string_view, std::string_view>, 11> sTextureFunctions{ {
            { "texture2D", "texture" },
            { "texture2DLod", "textureLod" },
            { "texture2DProj", "textureProj" },
            { "texture2DProjLod", "textureProjLod" },
            { "texture3D", "texture" },
            { "texture3DLod", "textureLod" },
            { "textureCube", "texture" },
            { "textureCubeLod", "textureLod" },
            { "texture2DArray", "texture" },
            { "texture2DArrayLod", "textureLod" },
            { "texture1D", "texture" },
        } };

        std::string_view attributeType(std::string_view name)
        {
            return name == "osg_Normal" ? "vec3" : "vec4";
        }

        // Rewrites the identifiers of one source. Comments and number literals are copied untouched.
        std::string rewriteIdentifiers(const std::string& source, GlslStage stage, GlslTarget target, Needs& needs)
        {
            const bool gles = target == GlslTarget::Gles;
            const bool vertex = stage == GlslStage::Vertex;
            std::string out;
            out.reserve(source.size() + source.size() / 16);

            for (std::size_t i = 0; i < source.size();)
            {
                const char c = source[i];
                if (c == '/' && i + 1 < source.size() && source[i + 1] == '/')
                {
                    const std::size_t end = source.find('\n', i);
                    const std::size_t stop = end == std::string::npos ? source.size() : end;
                    out.append(source, i, stop - i);
                    i = stop;
                    continue;
                }
                if (c == '/' && i + 1 < source.size() && source[i + 1] == '*')
                {
                    const std::size_t end = source.find("*/", i + 2);
                    const std::size_t stop = end == std::string::npos ? source.size() : end + 2;
                    out.append(source, i, stop - i);
                    i = stop;
                    continue;
                }
                if (std::isdigit(static_cast<unsigned char>(c))
                    || (c == '.' && i + 1 < source.size() && std::isdigit(static_cast<unsigned char>(source[i + 1]))))
                {
                    // a number: 1.0e-3, 0x1F, 2u: the exponent sign is part of it
                    std::size_t j = i + 1;
                    while (j < source.size())
                    {
                        const char d = source[j];
                        if (isIdentChar(d) || d == '.')
                            ++j;
                        else if ((d == '+' || d == '-') && (source[j - 1] == 'e' || source[j - 1] == 'E')
                            && !(source.size() > i + 1 && (source[i + 1] == 'x' || source[i + 1] == 'X')))
                            ++j;
                        else
                            break;
                    }
                    out.append(source, i, j - i);
                    i = j;
                    continue;
                }
                if (!isIdentStart(c))
                {
                    out.push_back(c);
                    ++i;
                    continue;
                }

                std::size_t j = i + 1;
                while (j < source.size() && isIdentChar(source[j]))
                    ++j;
                const std::string_view word(source.data() + i, j - i);
                i = j;

                const auto emit = [&](std::string_view replacement) { out.append(replacement); };

                // ---- matrices, in every stage the OSG build cannot feed through the built-in ----
                const bool matricesHere = gles || !vertex;
                if (matricesHere && word == "gl_ModelViewMatrix")
                {
                    needs.modelView = true;
                    emit("osg_ModelViewMatrix");
                    continue;
                }
                if (matricesHere && word == "gl_ModelViewMatrixInverse")
                {
                    needs.modelView = true;
                    if (gles)
                        emit("inverse(osg_ModelViewMatrix)");
                    else
                    {
                        needs.inverse = true;
                        emit("omw_inverse(osg_ModelViewMatrix)");
                    }
                    continue;
                }
                if (matricesHere && word == "gl_ProjectionMatrix")
                {
                    needs.projection = true;
                    emit("osg_ProjectionMatrix");
                    continue;
                }
                if (matricesHere && word == "gl_ModelViewProjectionMatrix")
                {
                    needs.modelViewProjection = true;
                    emit("osg_ModelViewProjectionMatrix");
                    continue;
                }
                if (matricesHere && word == "gl_NormalMatrix")
                {
                    needs.normalMatrix = true;
                    emit("osg_NormalMatrix");
                    continue;
                }

                if (!gles)
                {
                    out.append(word);
                    continue;
                }

                // ---- OpenGL ES 3.2 only below ----
                if (word == "attribute")
                {
                    emit("in");
                    continue;
                }
                if (word == "varying")
                {
                    emit(vertex ? "out" : "in");
                    continue;
                }

                if (vertex)
                {
                    std::string_view osgName;
                    if (word == "gl_Vertex")
                        osgName = "osg_Vertex";
                    else if (word == "gl_Normal")
                        osgName = "osg_Normal";
                    else if (word == "gl_Color")
                        osgName = "osg_Color";
                    else if (word == "gl_SecondaryColor")
                        osgName = "osg_SecondaryColor";
                    else if (word == "gl_FogCoord")
                        osgName = "osg_FogCoord";
                    else if (word.starts_with("gl_MultiTexCoord") && word.size() > 16
                        && std::isdigit(static_cast<unsigned char>(word[16])))
                    {
                        needs.attributes.emplace(std::string("osg_MultiTexCoord") + std::string(word.substr(16)));
                        out += "osg_MultiTexCoord";
                        out.append(word.substr(16));
                        continue;
                    }
                    if (!osgName.empty())
                    {
                        needs.attributes.emplace(osgName);
                        emit(osgName);
                        continue;
                    }
                    if (word == "gl_ClipVertex")
                    {
                        // no clip vertex in OpenGL ES: the value goes nowhere
                        needs.clipVertexSink = true;
                        emit("omw_ClipVertexSink");
                        continue;
                    }
                }
                else
                {
                    if (word == "gl_FragColor")
                    {
                        needs.fragDataOutputs.insert(0);
                        emit("omw_FragData0");
                        continue;
                    }
                    if (word == "gl_FragData")
                    {
                        // gl_FragData[N]
                        std::size_t k = i;
                        while (k < source.size() && source[k] == ' ')
                            ++k;
                        if (k < source.size() && source[k] == '[')
                        {
                            std::size_t digits = k + 1;
                            while (digits < source.size() && std::isdigit(static_cast<unsigned char>(source[digits])))
                                ++digits;
                            if (digits > k + 1 && digits < source.size() && source[digits] == ']')
                            {
                                const int index = std::stoi(source.substr(k + 1, digits - k - 1));
                                needs.fragDataOutputs.insert(index);
                                out += "omw_FragData" + std::to_string(index);
                                i = digits + 1;
                                continue;
                            }
                        }
                    }
                }

                if (word == "shadow2DProj")
                {
                    needs.shadow2DProj = true;
                    emit("omw_shadow2DProj");
                    continue;
                }
                if (gles && word == "textureSize2D")
                {
                    needs.textureSize2D = true;
                    emit("omw_textureSize2D");
                    continue;
                }
                if (word == "shadow2D")
                {
                    needs.shadow2D = true;
                    emit("omw_shadow2D");
                    continue;
                }
                bool replaced = false;
                for (const auto& [from, to] : sTextureFunctions)
                {
                    if (word == from)
                    {
                        emit(to);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced)
                    out.append(word);
            }
            return out;
        }

        // OpenGL ES has no initializers on uniforms: "uniform bool x = true;" becomes "uniform bool x;". The program is
        // given the value once it is linked (uniformInitializers below, called from the hooks).
        std::string stripUniformInitializers(const std::string& source)
        {
            std::string out;
            out.reserve(source.size());
            std::size_t lineStart = 0;
            while (lineStart < source.size())
            {
                std::size_t lineEnd = source.find('\n', lineStart);
                const std::size_t next = lineEnd == std::string::npos ? source.size() : lineEnd + 1;
                const std::size_t first = source.find_first_not_of(" \t", lineStart);
                if (first != std::string::npos && first < next && source.compare(first, 8, "uniform ") == 0)
                {
                    const std::size_t semicolon = source.find(';', first);
                    const std::size_t equals = source.find('=', first);
                    if (equals != std::string::npos && semicolon != std::string::npos && equals < semicolon
                        && equals < next)
                    {
                        std::size_t keep = equals;
                        while (keep > first && (source[keep - 1] == ' ' || source[keep - 1] == '\t'))
                            --keep;
                        out.append(source, lineStart, keep - lineStart);
                        out.append(source, semicolon, next - semicolon);
                        lineStart = next;
                        continue;
                    }
                }
                out.append(source, lineStart, next - lineStart);
                lineStart = next;
            }
            return out;
        }

        // The Adreno compiler rejects a call that expands to nothing ("#define applyClipPlanes()" followed by
        // "applyClipPlanes();" is a syntax error there, with or without a body in the macro): such a macro is replaced
        // by a function of the same name, defined where the macro was (so under the same #if) and once, as a header
        // that several merged libraries include repeats it
        std::string replaceEmptyMacros(const std::string& source)
        {
            std::string out;
            out.reserve(source.size());
            std::size_t lineStart = 0;
            while (lineStart < source.size())
            {
                std::size_t lineEnd = source.find('\n', lineStart);
                if (lineEnd == std::string::npos)
                    lineEnd = source.size();
                std::string_view line(source.data() + lineStart, lineEnd - lineStart);
                std::size_t i = line.find_first_not_of(" \t");
                bool emptyMacro = false;
                if (i != std::string_view::npos && line.substr(i).starts_with("#define"))
                {
                    i = line.find_first_not_of(" \t", i + 7);
                    const std::size_t nameStart = i;
                    while (i != std::string_view::npos && i < line.size()
                        && (std::isalnum(static_cast<unsigned char>(line[i])) || line[i] == '_'))
                        ++i;
                    // a name directly followed by "()" and nothing else
                    if (nameStart != std::string_view::npos && i != nameStart && i < line.size()
                        && line.substr(i).starts_with("()")
                        && line.find_first_not_of(" \t\r", i + 2) == std::string_view::npos)
                    {
                        emptyMacro = true;
                        const std::string name(line.substr(nameStart, i - nameStart));
                        out += "#ifndef OMW_FUNCTION_" + name + "\n#define OMW_FUNCTION_" + name + "\nvoid " + name
                            + "() {}\n#endif";
                    }
                }
                if (!emptyMacro)
                    out.append(line);
                if (lineEnd < source.size())
                    out += '\n';
                lineStart = lineEnd + 1;
            }
            return out;
        }

        // Integer and bit operations, texture arrays: all core in OpenGL ES 3, and the extensions do not exist there.
        // The clip distance extension is dropped from wherever a merged library put it; translateGlsl adds it back at the top
        std::string stripDesktopExtensions(const std::string& source)
        {
            std::string out;
            out.reserve(source.size());
            std::size_t lineStart = 0;
            while (lineStart < source.size())
            {
                std::size_t lineEnd = source.find('\n', lineStart);
                const std::size_t next = lineEnd == std::string::npos ? source.size() : lineEnd + 1;
                const std::size_t first = source.find_first_not_of(" \t", lineStart);
                const bool extension = first != std::string::npos && first < next && source.compare(first, 10, "#extension") == 0;
                const std::string_view line(source.data() + lineStart, next - lineStart);
                if (extension
                    && (line.find("GL_EXT_gpu_shader4") != std::string_view::npos
                        || line.find("GL_EXT_texture_array") != std::string_view::npos
                        || line.find("GL_EXT_clip_cull_distance") != std::string_view::npos))
                    out += "\n"; // keeps the line numbers
                else
                    out.append(line);
                lineStart = next;
            }
            return out;
        }

        // Offset where declarations can go: after #version and every #extension (which must precede any code), before the
        // first line of code, and outside any #if block (an inactive block would drop them).
        std::size_t findInsertionPoint(const std::string& source, std::size_t afterVersion)
        {
            std::size_t blockStart = afterVersion;
            int depth = 0;
            bool inComment = false;
            std::size_t lineStart = afterVersion;
            while (lineStart < source.size())
            {
                std::size_t lineEnd = source.find('\n', lineStart);
                if (lineEnd == std::string::npos)
                    lineEnd = source.size();
                std::string_view line(source.data() + lineStart, lineEnd - lineStart);
                std::size_t first = line.find_first_not_of(" \t\r");
                bool code = false;
                if (inComment)
                {
                    const std::size_t close = line.find("*/");
                    if (close != std::string_view::npos)
                    {
                        inComment = false;
                        line.remove_prefix(close + 2);
                        first = line.find_first_not_of(" \t\r");
                        if (first != std::string_view::npos && !line.substr(first).starts_with("//"))
                            code = line[first] != '#';
                    }
                }
                else if (first != std::string_view::npos)
                {
                    const std::string_view text = line.substr(first);
                    if (text.starts_with("//"))
                    {
                    }
                    else if (text.starts_with("/*"))
                    {
                        if (text.find("*/") == std::string_view::npos)
                            inComment = true;
                    }
                    else if (text[0] == '#')
                    {
                        std::size_t word = text.find_first_not_of(" \t", 1);
                        const std::string_view directive
                            = word == std::string_view::npos ? std::string_view() : text.substr(word);
                        if (directive.starts_with("if"))
                        {
                            if (depth == 0)
                                blockStart = lineStart;
                            ++depth;
                        }
                        else if (directive.starts_with("endif"))
                        {
                            if (depth > 0)
                                --depth;
                        }
                    }
                    else
                        code = true;
                }
                if (code)
                    return depth == 0 ? lineStart : blockStart;
                lineStart = lineEnd + 1;
            }
            return source.size();
        }

        constexpr std::string_view sInverseHelper
            = "mat4 omw_inverse(mat4 m) {"
              " float a00 = m[0][0], a01 = m[0][1], a02 = m[0][2], a03 = m[0][3];"
              " float a10 = m[1][0], a11 = m[1][1], a12 = m[1][2], a13 = m[1][3];"
              " float a20 = m[2][0], a21 = m[2][1], a22 = m[2][2], a23 = m[2][3];"
              " float a30 = m[3][0], a31 = m[3][1], a32 = m[3][2], a33 = m[3][3];"
              " float b00 = a00 * a11 - a01 * a10; float b01 = a00 * a12 - a02 * a10;"
              " float b02 = a00 * a13 - a03 * a10; float b03 = a01 * a12 - a02 * a11;"
              " float b04 = a01 * a13 - a03 * a11; float b05 = a02 * a13 - a03 * a12;"
              " float b06 = a20 * a31 - a21 * a30; float b07 = a20 * a32 - a22 * a30;"
              " float b08 = a20 * a33 - a23 * a30; float b09 = a21 * a32 - a22 * a31;"
              " float b10 = a21 * a33 - a23 * a31; float b11 = a22 * a33 - a23 * a32;"
              " float det = 1.0 / (b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06);"
              " return mat4("
              "(a11 * b11 - a12 * b10 + a13 * b09) * det, (a02 * b10 - a01 * b11 - a03 * b09) * det,"
              " (a31 * b05 - a32 * b04 + a33 * b03) * det, (a22 * b04 - a21 * b05 - a23 * b03) * det,"
              " (a12 * b08 - a10 * b11 - a13 * b07) * det, (a00 * b11 - a02 * b08 + a03 * b07) * det,"
              " (a32 * b02 - a30 * b05 - a33 * b01) * det, (a20 * b05 - a22 * b02 + a23 * b01) * det,"
              " (a10 * b10 - a11 * b08 + a13 * b06) * det, (a01 * b08 - a00 * b10 - a03 * b06) * det,"
              " (a30 * b04 - a31 * b02 + a33 * b00) * det, (a21 * b02 - a20 * b04 - a23 * b00) * det,"
              " (a11 * b07 - a10 * b09 - a12 * b06) * det, (a00 * b09 - a01 * b07 + a02 * b06) * det,"
              " (a31 * b01 - a30 * b03 - a32 * b00) * det, (a20 * b03 - a21 * b01 + a22 * b00) * det); }";

        std::string buildDeclarations(const Needs& needs, GlslStage stage, GlslTarget target)
        {
            const bool gles = target == GlslTarget::Gles;
            std::string out;
            if (gles)
                out += "precision highp float; precision highp int; precision highp sampler2D; precision highp sampler3D;"
                       " precision highp samplerCube; precision highp sampler2DShadow; precision highp sampler2DArray;";
            if (needs.modelView)
                out += " uniform mat4 osg_ModelViewMatrix;";
            if (needs.projection)
                out += " uniform mat4 osg_ProjectionMatrix;";
            if (needs.modelViewProjection)
                out += " uniform mat4 osg_ModelViewProjectionMatrix;";
            if (needs.normalMatrix)
                out += " uniform mat3 osg_NormalMatrix;";
            if (needs.inverse)
            {
                out += ' ';
                out += sInverseHelper;
            }
            if (gles)
            {
                for (const std::string& attribute : needs.attributes)
                    out += " in " + std::string(attributeType(attribute)) + " " + attribute + ";";
                if (needs.clipVertexSink)
                    out += " vec4 omw_ClipVertexSink;";
                if (needs.clipPlanes)
                {
                    out += ' ';
                    out += sClipPlanesHelper;
                }
                if (stage == GlslStage::Fragment)
                    for (int index : needs.fragDataOutputs)
                        out += " layout(location = " + std::to_string(index) + ") out vec4 omw_FragData"
                            + std::to_string(index) + ";";
                if (needs.shadow2DProj)
                    out += " vec4 omw_shadow2DProj(sampler2DShadow s, vec4 c) { return vec4(textureProj(s, c)); }";
                if (needs.textureSize2D)
                    out += " vec2 omw_textureSize2D(sampler2D s, int l) { return vec2(textureSize(s, l)); }";
                if (needs.shadow2D)
                    out += " vec4 omw_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }";
            }
            return out;
        }
    }

    unsigned int glesVersion()
    {
        static const unsigned int version = [] {
            const char* env = std::getenv("OPENMW_GLES_VERSION");
            const std::string_view value = env ? env : "";
            if (value == "30" || value == "3.0")
                return 300u;
            if (value == "31" || value == "3.1")
                return 310u;
            return 320u;
        }();
        return version;
    }

    GlslTarget glslTarget()
    {
        static const GlslTarget target = std::getenv("OSG_USE_ZINK") != nullptr ? GlslTarget::DesktopCompat : GlslTarget::Gles;
        return target;
    }

    void setClipDistancesAvailable(bool available)
    {
        sClipDistancesAvailable = available;
    }

    std::vector<UniformInitializer> uniformInitializers(const std::string& source)
    {
        static const std::regex declaration(
            R"((?:^|\n)[ \t]*uniform\s+(?:(?:lowp|mediump|highp)\s+)?([biu]?vec[234]|bool|int|uint|float)\s+(\w+)\s*=\s*([^;]+);)");
        static const std::regex number(R"([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?[uUfF]?)");
        std::vector<UniformInitializer> result;
        for (std::sregex_iterator it(source.begin(), source.end(), declaration), end; it != end; ++it)
        {
            UniformInitializer uniform{ (*it)[2], (*it)[1], {} };
            const std::string type = uniform.type;
            const std::size_t components = std::isdigit(static_cast<unsigned char>(type.back())) ? type.back() - '0' : 1;
            std::string value = (*it)[3];
            // "vec2(1.0, 1.0)": the arguments; a single literal stands for itself
            const std::size_t open = value.find('(');
            if (open != std::string::npos)
            {
                std::string constructor = value.substr(0, open);
                constructor.erase(constructor.find_last_not_of(" \t") + 1);
                if (constructor != type)
                    continue;
                const std::size_t close = value.rfind(')');
                if (close == std::string::npos || close < open)
                    continue;
                value = value.substr(open + 1, close - open - 1);
            }
            bool valid = true;
            std::size_t start = 0;
            while (valid && start <= value.size())
            {
                std::size_t comma = value.find(',', start);
                if (comma == std::string::npos)
                    comma = value.size();
                std::string argument = value.substr(start, comma - start);
                argument.erase(0, argument.find_first_not_of(" \t\n"));
                argument.erase(argument.find_last_not_of(" \t\n") + 1);
                if (argument == "true" || argument == "false")
                    uniform.values.push_back(argument == "true" ? 1.f : 0.f);
                else if (std::regex_match(argument, number))
                    uniform.values.push_back(std::strtof(argument.c_str(), nullptr));
                else
                    valid = false;
                start = comma + 1;
            }
            if (!valid)
                continue;
            if (uniform.values.size() == 1 && components > 1)
                uniform.values.resize(components, uniform.values[0]);
            if (uniform.values.size() == components)
                result.push_back(std::move(uniform));
        }
        return result;
    }

    const char* translatorStamp()
    {
        return __DATE__ " " __TIME__;
    }

    static std::string translateGlslText(const std::string& source, GlslStage stage, GlslTarget target, bool depthClamp)
    {
        if (target == GlslTarget::Desktop)
            return source;

        const std::size_t versionPos = source.find("#version ");
        if (versionPos == std::string::npos)
            return source;
        const std::size_t versionEnd = source.find('\n', versionPos);
        if (versionEnd == std::string::npos)
            return source;
        const std::string_view versionLine(source.data() + versionPos, versionEnd - versionPos);
        if (versionLine.find("120") == std::string_view::npos)
            return source; // already translated, or not a compatibility shader

        // desktop: only fragment shaders have anything to translate
        if (target == GlslTarget::DesktopCompat && stage == GlslStage::Vertex)
            return source;

        Needs needs;
        const bool clipDistances
            = target == GlslTarget::Gles && stage == GlslStage::Vertex && sClipDistancesAvailable;
        std::string translated
            = rewriteIdentifiers(clipDistances ? replaceClipVertex(source, needs) : source, stage, target, needs);
        if (target == GlslTarget::Gles)
            translated = stripDesktopExtensions(stripUniformInitializers(translated));
        if (target == GlslTarget::Gles && stage == GlslStage::Vertex && depthClamp)
            translated = wrapMainForDepthClamp(translated);
        if (target == GlslTarget::Gles)
            translated = replaceEmptyMacros(translated);

        // the version line sits at the same offset: the rewrite does not touch "#version 120" (no identifier of ours in it)
        const std::size_t newVersionEnd = translated.find('\n', versionPos);
        if (target == GlslTarget::Gles)
            translated.replace(versionPos, newVersionEnd - versionPos, "#version " + std::to_string(glesVersion()) + " es");

        std::string declarations = buildDeclarations(needs, stage, target);
        if (target == GlslTarget::Gles && stage == GlslStage::Vertex
            && (needs.clipPlanes || translated.find("gl_ClipDistance") != std::string::npos))
            declarations.insert(0, "#extension GL_EXT_clip_cull_distance : enable\n");
        if (!declarations.empty())
        {
            const std::size_t afterVersion = translated.find('\n', versionPos) + 1;
            const std::size_t at = findInsertionPoint(translated, afterVersion);
            translated.insert(at, declarations + "\n");
        }
        return translated;
    }

    std::string translateGlsl(const std::string& source, GlslStage stage, GlslTarget target, bool depthClamp)
    {
        // The compute shaders are written for desktop GLSL and are not translated as text
        std::string translated
            = stage == GlslStage::Compute ? source : translateGlslText(source, stage, target, depthClamp);
        if (target == GlslTarget::Gles)
            translated = pruneUnusedStorageBlocks(translated);
        // Regardless of what the text translation did or skipped, the default sends the result through glslang and SPIRV-Cross
        if (target == GlslTarget::Gles && spirvConversionEnabled())
            return convertGlslToGles(translated, stage, glesVersion());
        return translated;
    }

    bool computeShadersAvailable()
    {
        // OpenGL ES 3.0 has no compute shaders at all
        if (glslTarget() == GlslTarget::Gles && glesVersion() < 310)
            return false;
        return glslTarget() != GlslTarget::Gles || spirvConversionEnabled();
    }
}
