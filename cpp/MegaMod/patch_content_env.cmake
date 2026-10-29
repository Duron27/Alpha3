# Makes prepareContentDir honor UQM_CONTENT_DIR, like the other directories already honor their UQM_*_DIR variables.
# Idempotent: does nothing when already patched.
file(READ "${FILE}" src)
if(src MATCHES "UQM_CONTENT_DIR")
    return()
endif()
set(needle "\tif (contentDirName == NULL)\n\t{\n\t\tchar buf[PATH_MAX];\n")
string(FIND "${src}" "${needle}" pos)
if(pos EQUAL -1)
    message(FATAL_ERROR "prepareContentDir changed upstream, update patch_content_env.cmake")
endif()
string(REPLACE "${needle}"
    "\tif (contentDirName == NULL)\n\t\tcontentDirName = getenv (\"UQM_CONTENT_DIR\");\n\n${needle}" src "${src}")
file(WRITE "${FILE}" "${src}")
