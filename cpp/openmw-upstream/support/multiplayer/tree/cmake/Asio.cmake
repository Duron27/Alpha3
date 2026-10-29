# Standalone Asio (header-only), used by components/net (the multiplayer sockets) and apps/mpserver. Defines the interface target
# openmw-asio: the include path, ASIO_STANDALONE and the Windows version Asio wants. Included by the main project and by the
# server-only project (apps/mpserver/standalone).
#
# Like OpenMW's other dependencies it is downloaded at configure time (pinned and checked by its hash) unless
# OPENMW_USE_SYSTEM_ASIO is on, which uses the asio.hpp that is installed (asio-devel, libasio-dev, ...). For a build without
# network access, point FETCHCONTENT_SOURCE_DIR_ASIO at an unpacked Asio release.
if (TARGET openmw-asio)
    return()
endif ()

option(OPENMW_USE_SYSTEM_ASIO "Use the installed standalone Asio headers instead of downloading them" OFF)

if (OPENMW_USE_SYSTEM_ASIO)
    find_path(OPENMW_ASIO_INCLUDE_DIR NAMES asio.hpp)
    if (NOT OPENMW_ASIO_INCLUDE_DIR)
        message(FATAL_ERROR "OPENMW_USE_SYSTEM_ASIO is on but asio.hpp was not found: install Asio (asio-devel, libasio-dev) "
            "or turn the option off to download it")
    endif ()
    set(asio_include "${OPENMW_ASIO_INCLUDE_DIR}")
else ()
    include(FetchContent)
    FetchContent_Declare(asio
        URL https://github.com/chriskohlhoff/asio/archive/refs/tags/asio-1-30-2.tar.gz
        URL_HASH SHA512=cfba1998d4f666156e751c4cab1e504441b368efbf344e22de30f8b983670a88d045d3ca82f197b95522a2026262274f93bc3061210781ce30c35c71a386ce6e
        SOURCE_DIR "${CMAKE_BINARY_DIR}/fetched/asio"
    )
    # (the archive has no CMakeLists.txt: this only downloads and unpacks it)
    FetchContent_MakeAvailable(asio)
    set(asio_include "${asio_SOURCE_DIR}/asio/include")
endif ()

add_library(openmw-asio INTERFACE)
target_include_directories(openmw-asio SYSTEM INTERFACE "${asio_include}")
target_compile_definitions(openmw-asio INTERFACE ASIO_STANDALONE ASIO_NO_DEPRECATED)
if (WIN32)
    target_compile_definitions(openmw-asio INTERFACE _WIN32_WINNT=0x0A00)
endif ()
