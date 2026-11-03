// Post-processing shaders made cheaper as OpenMW loads them (patch 0023 calls rewriteTechnique with each .omwfx file's
// text before it is parsed). The files on disk stay as they are; every rewrite is its own setting, off by default, and
// is skipped (the shader loaded as it is, the reason in the log) when the text it looks for is not there, as after an
// update of the shader.
//
// [Experimental] wetworld fast reflections: Rafael's Shader Pack's wetworld traces a reflection ray for every wet pixel
// of the screen in its full size "wet" pass. Rewritten, a "reflect" pass a quarter of the size (half the width and
// height) traces them, with wetworld's own code for the surface and the ray, into a render target of its own (RT_SSR,
// colour premultiplied by the reflection's strength); the wet pass reads that instead of tracing, and everything else in
// it (puddles, ripples, the wet ground's colour, the rain) stays at full size.
#include "support.hpp"

#include <string>
#include <string_view>

#include <alpha3/upstream.hpp>
#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        bool wetworldFastReflections()
        {
            static const bool on = settingBool("Experimental", "wetworld fast reflections", false);
            return on;
        }

        // Replaces the one occurrence of [from]; false when it is not there exactly once
        bool replaceOnce(std::string& text, std::string_view from, std::string_view to)
        {
            const std::size_t at = text.find(from);
            if (at == std::string::npos || text.find(from, at + from.size()) != std::string::npos)
                return false;
            text.replace(at, from.size(), to);
            return true;
        }

        constexpr std::string_view sWetHeader = "fragment wet(rt1=RT_H, rt2=RT_P)";
        constexpr std::string_view sWetMain = "\n    // Wetworld\n    void main()";
        constexpr std::string_view sTraceCall
            = "ssrResult = ComputeSSR(ssrNormal, viewPos, float4(color.rgb, 1.0f - uWorldSpecularSmoothness), "
              "worldDistance, pixelIsWater, fresnel * ssrIntensity, mss, ramn);";
        constexpr std::string_view sPasses = "passes = ds, particles, wet;";
        constexpr std::string_view sParticlesTarget = "render_target RT_P\n{";

        constexpr std::string_view sSsrTarget = R"(render_target RT_SSR
{
    width_ratio  = 0.5;
    height_ratio = 0.5;
    internal_format = rgba;
    source_type = byte;
    source_format = rgba;
}

)";

        // The reflect pass's main: the wet pass's main up to its ComputeSSR call, without what only colours the pixel
        constexpr std::string_view sReflectMain = R"(
    // Alpha3: the reflections at half size (src/techniquerewrite.cpp)
    void main()
    {
        omw_FragColor = float4(0.0f);
        if (omw.isUnderwater && !UnderWaterSSRActive)
            return;

        bool skipFX = omw.isInterior || omw.isUnderwater;
        float3 viewPos = ReconstructViewPos(omw_TexCoord);
        float3 pos = ReconstructWorldPos(viewPos);
        float worldDistance = distance(omw.eyePos.xyz, pos);
        float waterHeight = omw.waterHeight;
        float3 viewDir = normalize(pos - omw.eyePos.xyz);

        float ramn = skipFX ? 0.0f : saturate(GetRainAmountNormalized());
        float ramp = skipFX ? 0.0f : saturate(GetRainAmount());

        bool pixelIsWater = false;
        if (worldDistance < FogFar || omw.isUnderwater)
        {
            float zTol = max(200.0f, worldDistance) / omw.projectionMatrix[1][1] * omw.rcpResolution.y;
            pixelIsWater = omw.isWaterEnabled && (omw.isUnderwater ? (waterHeight - pos.z) <= zTol : (pos.z - waterHeight) <= zTol);
            if (pos.x > -11700.0f && pos.x < -11120.0f && pos.y > -75321.0f && pos.y < -74200.0f)
                pixelIsWater = false;
        }

        if (!(worldDistance < FogFar || pixelIsWater))
            return;
        if (skipFX && !pixelIsWater)
            return;

        if (pixelIsWater)
        {
            if ((!omw.isUnderwater && viewDir.z < 0.0f) || (omw.isUnderwater && viewDir.z > 0.0f))
            {
                float t = (omw.waterHeight - omw.eyePos.z) / viewDir.z;
                if (t >= 0.0f)
                {
                    pos = t * viewDir + omw.eyePos.xyz;
                    worldDistance = distance(omw.eyePos.xyz, pos);
                }
            }
        }

        float3 wnormal = ReconstructWorldNormal(viewPos);
        if (uRealNormals && !pixelIsWater)
        {
            #if defined(OMW_API_VERSION)
                float3 wnormal2 = omw_GetNormalsWorldSpace(omw_TexCoord);
            #else
                float3 wnormal2 = omw_GetNormals(omw_TexCoord);
            #endif
            float similarity = saturate(dot(wnormal, wnormal2));
            similarity = smoothstep(0.966f, 1.0f, similarity) - 2.0f * distance(length(wnormal2), 1.0f);
            wnormal = lerp(wnormal, wnormal2, saturate(max(similarity, worldDistance * 0.0005f)));
            similarity = length(wnormal);
            wnormal = similarity > 0.0f ? wnormal / similarity : float3(0.0f);
        }
        else if (pixelIsWater)
        {
            #if defined(OMW_API_VERSION)
            if (RealWaterNormals)
                wnormal = omw_GetNormalsWorldSpace(omw_TexCoord);
            else
            #endif
            {
                float simTime = omw.simulationTime * 0.5f;
                float2 fakePos = -pos.xy * 0.04f;
                wnormal.xyz = float3(0.0f);
                float2 offset = float2(simTime, -simTime * 0.5f);
                float2 wavesOffset = 1.7f * sin(float2(fakePos.y * 0.5f, fakePos.x * 0.5f) * 0.7f + simTime);
                float2 waves = float2(
                    cos((fakePos.x + offset.x + wavesOffset.x) * 2.5f),
                    cos((fakePos.y + offset.y + wavesOffset.y) * 3.0f)
                    ) * 0.5;
                waves += 0.15 * sin(fakePos.xy * 1.2f + simTime);
                waves *= 0.1f;
                wnormal.xy += waves * smoothstep(1000.0f, 50.0f, omw.eyePos.z - waterHeight);
                float2 grad = normalize(waves + float2(0.001f, -0.001f));
                float2 detailOffset = fakePos * 2.0f + grad * 1.2f + simTime * 1.3f;
                float2 detailWave = 0.03f * sin(detailOffset * float2(3.5f, 2.1f) + float2(1.0f, -1.5f) * simTime);
                wnormal.xy += detailWave * smoothstep(1000.0f, 50.0f, omw.eyePos.z - waterHeight);
                wnormal.z = sqrt(1.0f - dot(wnormal.xy, wnormal.xy));
            }
        }
        float wmask = smoothstep(0.6f, 1.0f, wnormal.z);

        if (omw.isUnderwater && pixelIsWater && wnormal.z > 0.0f)
            wnormal = -wnormal;

        if (ReflectionElongation && !omw.isInterior)
        {
            wnormal.xy = lerp(wnormal.xy, viewDir.xy, -viewDir.z * (pixelIsWater ? 0.20f : 0.10f));
            float len2 = dot(wnormal, wnormal);
            wnormal = len2 > 0.0f ? wnormal * inversesqrt(len2) : float3(0.0f);
        }

        bool rainEnabled = uPuddlesEnabled || uWorldWetnessEnabled;
        if (!((rainEnabled && ramp != 0.0f || uWaterSSRActive) && pos.z - 600.0f <= omw.eyePos.z))
            return;

        float ssrIntensity = 0.0f;
        float3 ssrNormal = float3(0.0f, 0.0f, -1.0f);
        float fresnel = 0.0f;
        float mss = 1.0f;

        bool isUp = true;
        float cameraDot = dot(viewDir.xyz, wnormal.xyz);
        if (cameraDot < -0.75f || dot(wnormal.xyz, float3(0.0, 0.0, omw.isUnderwater ? -1.0 : 1.0)) < 0.75f)
            isUp = false;

        if (pixelIsWater || (wmask > 0.0f && worldDistance < 5000.0f && wnormal.z >= 0.0f))
        {
            float puddlemask = !uPuddlesEnabled ? 1.0f : CalculatePuddleMask(pos.xy / 1500.0, wnormal.xyz, ramn);
            float pwmask = pixelIsWater ? 0.0f : (1.0 - puddlemask) * wmask * smoothstep(omw.waterHeight, omw.waterHeight + 50.0f, pos.z);

            float3 ripnorm = CalculateRippleNormal(pos.xy / 250.0);
            ripnorm = float3(ripnorm.xy, ripnorm.z * lerp(lerp(1, 0.1, ramn), lerp(5, 0.1, ramn), pwmask));
            float len2 = dot(ripnorm, ripnorm);
            ripnorm = (len2 > 0.0f) ? ripnorm * inversesqrt(len2) : float3(0.0f, 0.0f, 1.0f);
            RotateRippleNormal(ripnorm, wnormal);

            float rainAmount = ramp * smoothstep(5000.0f, 2500.0f, worldDistance);
            ssrNormal = lerp(wnormal, ripnorm, uRippleNormalIntensity * rainAmount * wmask);
            ssrNormal = lerp(ssrNormal, float3(0.0f, 0.0f, 1.0f), uNormalStraighteningIntensity);

            fresnel = saturate(fresnel_dielectric(dot(-viewDir, ssrNormal), 1.333f));
            #if !NO_SKY
            if (!omw.isUnderwater)
                mss = CalculateReflectionAttenuation(ssrNormal) * smoothstep(12000.0f, 0.0f, worldDistance * (1.0f - uNormalStraighteningIntensity));
            #endif

            ssrIntensity = pixelIsWater ? WaterReflectionIntensity : uPuddleReflectionIntensity * pwmask + uWorldReflectionIntensity;
            ssrIntensity *= (pixelIsWater && uWaterSSRActive ? 1.0f : rainAmount);
        }

        bool pixelCanReflect = (pixelIsWater && uWaterSSRActive)
            || (!pixelIsWater && uSSRActive && rainEnabled && uWorldWetnessEnabled);
        if (isUp && ssrIntensity > 0.0f && pixelCanReflect && fresnel > 0.0f)
        {
            float4 ssr = ComputeSSR(ssrNormal, viewPos, float4(0.0f, 0.0f, 0.0f, 1.0f - uWorldSpecularSmoothness),
                worldDistance, pixelIsWater, fresnel * ssrIntensity, mss, ramn);
            ssr.a = saturate(ssr.a);
            omw_FragColor = float4(ssr.rgb * ssr.a, ssr.a);
        }
    }
}

)";

        // The wet pass reads it: the colour back from premultiplied
        constexpr std::string_view sReadReflection
            = "ssrResult = omw_Texture2D(RT_SSR, omw_TexCoord); "
              "ssrResult.rgb /= max(ssrResult.a, 0.0001f); // Alpha3: traced at half size by the reflect pass";

        bool rewriteWetworld(std::string& source)
        {
            // (OpenMW does the same when it parses)
            for (std::size_t at = source.find("\r\n"); at != std::string::npos; at = source.find("\r\n", at))
                source.erase(at, 1);
            const std::size_t header = source.find(sWetHeader);
            const std::size_t open = header == std::string::npos ? header : source.find('{', header);
            const std::size_t main = open == std::string::npos ? open : source.find(sWetMain, open);
            if (main == std::string::npos)
                return false;
            // the wet pass's own functions (MarchRay, ComputeSSR, the normals, ...), for the reflect pass too
            const std::string helpers = source.substr(open + 1, main - open - 1);

            std::string text = source;
            if (!replaceOnce(text, sTraceCall, sReadReflection) || !replaceOnce(text, sPasses, "passes = ds, particles, a3reflect, wet;")
                || !replaceOnce(text, sWetHeader, "fragment wet(rt1=RT_H, rt2=RT_P, rt3=RT_SSR)")
                || !replaceOnce(text, sParticlesTarget, std::string(sSsrTarget) + std::string(sParticlesTarget)))
                return false;
            const std::string reflectPass
                = "fragment a3reflect(target=RT_SSR, rt1=RT_H)\n{" + helpers + std::string(sReflectMain);
            const std::size_t wet = text.find("fragment wet(");
            text.insert(wet, reflectPass);
            source = std::move(text);
            return true;
        }
    }

    void rewriteTechnique(std::string_view name, std::string& source)
    {
        if (name == "wetworld" && wetworldFastReflections())
        {
            if (rewriteWetworld(source))
                Log(Debug::Info) << "wetworld: reflections traced at half size ([Experimental] wetworld fast reflections)";
            else
                Log(Debug::Warning) << "wetworld: not the version [Experimental] wetworld fast reflections knows, "
                                       "loaded as it is";
        }
    }
}
