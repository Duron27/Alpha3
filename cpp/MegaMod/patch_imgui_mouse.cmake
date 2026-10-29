# UQM's ImGui menu on Android: draw ImGui's own mouse cursor while the menu is up (there is no system cursor), and
# let the app ask whether the menu is up (SDLActivity.isMouseShown, see jni_engine.c). Idempotent.
file(READ "${FILE}" src)
if(src MATCHES "UQM_ImGui_IsMenuVisible")
    return()
endif()

set(needle "\tcImGui_ImplSDLRenderer2_NewFrame ();\n\tcImGui_ImplSDL2_NewFrame ();\n\tImGui_NewFrame ();\n")
string(FIND "${src}" "${needle}" pos)
if(pos EQUAL -1)
    message(FATAL_ERROR "UQM_ImGui_Render changed upstream, update patch_imgui_mouse.cmake")
endif()
string(REPLACE "${needle}" "\tio->MouseDrawCursor = menu_visible;\n${needle}" src "${src}")

string(APPEND src "\n// Whether the menu is up and wants the pointer; read by the Android launcher (isMouseShown)\nint UQM_ImGui_IsMenuVisible (void)\n{\n\treturn imgui_initialized && menu_visible;\n}\n")
file(WRITE "${FILE}" "${src}")
