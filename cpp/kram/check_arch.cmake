# cmake -DLIB=<file> -P check_arch.cmake
# Fails unless <file> is a 64-bit ARM ELF library, so a build that picked up the host compiler cannot be packaged.
file(READ "${LIB}" header LIMIT 20 HEX)
string(SUBSTRING "${header}" 0 8 magic)         # 7f454c46 = "\x7fELF"
string(SUBSTRING "${header}" 36 4 machine)      # e_machine, little endian: b700 = AArch64
if(NOT magic STREQUAL "7f454c46" OR NOT machine STREQUAL "b700")
    message(FATAL_ERROR "${LIB} is not an arm64 ELF library (e_machine=${machine}). "
        "The host compiler was probably used instead of the NDK's.")
endif()
