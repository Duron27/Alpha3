cmake_minimum_required(VERSION 3.10)

project(glad C)

# Android: a shared library the app ships and the engine loads through SDL. iOS: static, linked into the engine (the GL
# functions themselves come from OpenGLES.framework at runtime, through SDL_GL_GetProcAddress)
if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    add_library(glad STATIC src/gles2.c)
    set_target_properties(glad PROPERTIES POSITION_INDEPENDENT_CODE ON)
else()
    add_library(glad SHARED src/gles2.c)
endif()

# Tell other targets where to find include headers 

target_include_directories(glad PUBLIC include)

# Installed into the vcpkg prefix by the top-level build so the openmw-osg port (android/glad.patch) can find it
install(TARGETS glad LIBRARY DESTINATION lib ARCHIVE DESTINATION lib RUNTIME DESTINATION bin)
install(DIRECTORY include/ DESTINATION include)
