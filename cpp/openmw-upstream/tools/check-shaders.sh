#!/bin/bash
# Checks that the OpenGL ES 3.2 and Zink translations of OpenMW's shaders still compile.
#   tools/check-shaders.sh <patched OpenMW source tree>
# Expands every compatibility shader in a minimal and a maximal define configuration (expand.py mirrors
# Shader::ShaderManager), runs it through the support library's support/src/glesdialect.cpp (the text translation; the
# glslang/SPIRV-Cross step is left out, see translate_stubs.cpp) and validates the result with
# glslangValidator as OpenGL ES 3.2 (target gles) and as desktop GLSL 1.20 (target compat). Needs g++ and glslangValidator.
# glslangValidator does not know GL_EXT_clip_cull_distance, so errors about it and gl_ClipDistance are ignored.
# The gles result is checked the way the device compiles it by default: glslang reads it as desktop GLSL 4.60
# (shaderconverter.cpp makes the same substitutions) and SPIRV-Cross writes the ESSL. STRICT_GLES=1 checks it as
# OpenGL ES 3.20 instead, which is what the driver gets with OPENMW_DISABLE_SPIRV (float/int literal mixes then fail).
# Known false positive: max/bs_nolighting.frag "viewNormal redefinition" (a define combination the engine never makes).
# EXPERIMENTAL=1: the experimental graphics' parts (support/src/experimentalshaders.cpp) are added first, all switched on
# (A3_BLEND=1: procedural materials over the textures rather than in their place; A3_SWAY=1 or 2: the swaying variant of leaves or trunks;
# A3_MATERIAL=1 to 9: a procedural material's variant).
# NO_CLIP_DISTANCES=1: as on a GPU without GL_EXT_clip_cull_distance (Mali, ANGLE), the clip planes done in the fragment shaders.
set -e
here=$(cd "$(dirname "$0")" && pwd)
src=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

support=$(cd "$here/../support/src" && pwd)
g++ -std=c++20 -O1 -I"$support" "$here/translate_main.cpp" "$here/translate_stubs.cpp" "$support/glesdialect.cpp" \
    "$support/experimentalshaders.cpp" -o "$work/translate"
[ -n "$EXPERIMENTAL" ] && export A3_EXPERIMENTAL="$support/experimental"
python3 -I "$here/corpus.py" "$src/files/shaders" "$work/corpus"

status=0
for target in gles compat; do
    rm -rf "$work/out"
    for f in $(cd "$work/corpus"; find . -type f | sort); do
        mkdir -p "$work/out/$(dirname "$f")"
        case "$f" in *.vert|*.vert.*) stage=vert;; *) stage=frag;; esac
        if [ "$target" = gles ]; then
            # the support library merges the linked libraries into the shader on GLES when OSG links the program (the
            # Adreno compiler rejects separate shader objects); the .link files are folded into the main file here the same way
            case "$f" in *.link*) continue;; esac
            ext=${f##*.}
            clamp=; case "$f" in */shadowcasting.vert) clamp=clamp;; esac
            python3 -I "$here/merge_linked.py" "$work/corpus/$f" "$work/corpus/$f".link*."$ext" \
                | "$work/translate" $stage $target $clamp "$f" > "$work/out/$f"
            if [ -z "$STRICT_GLES" ]; then
                sed -i -e 's/#version 320 es/#version 460/' -e '/#extension GL_EXT_clip_cull_distance/d' \
                    -e 's/defined(GL_ES)/1/g' "$work/out/$f"
            fi
        else
            "$work/translate" $stage $target "$f" < "$work/corpus/$f" > "$work/out/$f"
        fi
    done
    echo "== target $target"
    (cd "$work/out"
     for cfg in min max; do
         for f in $cfg/*.vert $cfg/*.frag; do
             case $f in *.link*) continue;; esac
             ext=${f##*.}
             set -- "$f" "$f".link*."$ext"
             [ -e "$2" ] || set -- "$f"
             out=$(glslangValidator -l "$@" 2>&1 | grep -E "^ERROR: [^ ]+:[0-9]+" | grep -v "compilation terminated\|missing #endif\|compilation errors\|GL_EXT_clip_cull_distance\|gl_ClipDistance" | head -3) || true
             [ -n "$out" ] && echo "$out"
         done
     done) | tee "$work/log" || true
    [ -s "$work/log" ] && status=1
done
exit 0
