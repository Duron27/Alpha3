# dethrace patch

dethrace is built from [dethrace-labs/dethrace](https://github.com/dethrace-labs/dethrace) at the commit named
`DETHRACE_COMMIT` in `../CMakeLists.txt`, with `duron27-android.patch` applied. The patch is what the
[Duron27-org/dethrace](https://github.com/Duron27-org/dethrace) fork has over upstream (its one commit, "Minimum changes
needed to build android"), moved onto the pinned commit:

* the game builds as `libdethrace.so` (`add_library(dethrace SHARED)`, linked to `SDL2::SDL2`) on Android, and `main.c`
  gets `SDL_main` from `<SDL_main.h>`;
* `src/harness/os/android.c` (and an `elseif(ANDROID)` before `elseif(UNIX)` in the harness CMake, so Android does not
  take `linux.c`), `Threads::Threads` for the libraries that need it.

## Making the patch again (a newer upstream commit)

    git clone https://github.com/Duron27-org/dethrace.git && cd dethrace
    git remote add upstream https://github.com/dethrace-labs/dethrace.git && git fetch upstream
    git checkout -b onto-upstream <new upstream commit>
    git cherry-pick 9448ebe                     # fix conflicts; new functions in src/harness/os/os.h need a stub in android.c
    git diff <new upstream commit> HEAD -- . ':!lib' > /path/to/alpha3_aio/cpp/dethrace/patches/duron27-android.patch

Set `DETHRACE_COMMIT` to the new commit. A build for arm64 tells whether it still works; upstream includes `<SDL.h>`,
which is why `SDL2_INCLUDE_DIR` is the `include/SDL2` directory and `SDL2_SDLMAIN_LIBRARY` is given.
