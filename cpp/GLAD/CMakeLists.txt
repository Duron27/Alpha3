cmake_minimum_required(VERSION 3.10)

project(glad C)

# Define the library target
add_library(glad SHARED src/gles2.c)

# Tell other targets where to find include headers 

target_include_directories(glad PUBLIC include)

# Installed into the vcpkg prefix by the top-level build so the openmw-osg port (android/glad.patch) can find it
install(TARGETS glad LIBRARY DESTINATION lib ARCHIVE DESTINATION lib RUNTIME DESTINATION bin)
install(DIRECTORY include/ DESTINATION include)
