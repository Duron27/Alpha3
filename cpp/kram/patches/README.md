# kram patches

kram is built from [alecazam/kram](https://github.com/alecazam/kram) at the commit named `KRAM_COMMIT` in `../CMakeLists.txt`,
with two patches applied in this order:

1. `duron27-differences.patch`: what the [Duron27/kram](https://github.com/Duron27/kram) fork has over upstream (a newer
   ASTC encoder and squish with BC2, 10x10 and 12x12 ASTC, pixel flipping for OpenGL formats and the KTX orientation
   flags from `-flip`, RGB888 input, aarch64 Linux and Android build fixes). The fork's commits `802c5ae..09bf2e9`
   (they start where the fork left upstream) rebased onto the pinned commit, as one diff. The macOS icons and the test
   trace, which the fork stores as Git LFS pointers, are left out.
2. `kram-shared-library.patch`: ours. Builds kramc as a shared library exporting `kram_main` (`KRAM_BUILD_SHARED`),
   which the app loads, and keeps kram's CMake from replacing the NDK compiler with the host's clang.

## Making `duron27-differences.patch` again (a newer upstream commit)

    export GIT_LFS_SKIP_SMUDGE=1
    git clone https://github.com/Duron27/kram.git && cd kram
    git config filter.lfs.clean cat; git config filter.lfs.smudge cat; git config filter.lfs.process ''; git config filter.lfs.required false
    git remote add upstream https://github.com/alecazam/kram.git && git fetch upstream
    git checkout -b onto-upstream <new upstream commit>
    git cherry-pick 802c5ae..09bf2e9            # the fork's commits; fix conflicts
    git diff <new upstream commit> -- . ':!kramv/Assets.xcassets' ':!kram-profile/kram-profile/Assets.xcassets' ':!tests/traces' \
        > /path/to/alpha3_aio/cpp/kram/patches/duron27-differences.patch

Set `KRAM_COMMIT` to the new commit and check that `kram-shared-library.patch` still applies after it
(`patch -p1 -t -N`; offsets are fine).
