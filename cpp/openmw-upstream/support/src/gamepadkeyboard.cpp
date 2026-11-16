// The gamepad keyboard's engine side (the Lua mod in support/gamepadkeyboard/, a built-in script list in resources/vfs).
// Lua draws the keyboard and reads the gamepad; it cannot type into the game's own text boxes (the console, a saved
// game's name, the character's and a spell's names, the inventory's search). This types into whichever MyGUI edit box
// has the key focus, as a keyboard's text does, and keeps the gamepad from the menus while the keyboard is open.
//
// Called through openmw.input (patch common/0035): _alpha3TextFocused, _alpha3TypeText, _alpha3PressKey and
// _alpha3CaptureGamepad, and _alpha3ToggleConsole for the mod's gamepad combo. The typing runs as a Lua delayed action, on the main thread.

#include <alpha3/upstream.hpp>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>

#include <components/debug/debuglog.hpp>

#include <MyGUI_EditBox.h>
#include <MyGUI_InputManager.h>
#include <MyGUI_KeyCode.h>
#include <MyGUI_RenderManager.h>
#include <MyGUI_Widget.h>

namespace Alpha3
{
    namespace
    {
        // the capture lasts this long after the last renewal: the mod renews it each frame while the keyboard is open,
        // so a script that stops (an error, the mod turned off, a reload) gives the gamepad back by itself
        constexpr auto sCaptureHold = std::chrono::milliseconds(400);
        std::atomic<int64_t> sCapturedUntil{ 0 };

        // the launcher's switch (Controls > Gamepad keyboard): OPENMW_NO_GAMEPAD_KEYBOARD=1 when it is off. The mod then
        // never sees a focused text box, never takes the gamepad, and its Back + Start combo does nothing
        bool keyboardOff()
        {
            static const bool off = [] {
                const char* value = std::getenv("OPENMW_NO_GAMEPAD_KEYBOARD");
                const bool off = value != nullptr && value[0] != '\0' && value[0] != '0';
                if (off)
                    Log(Debug::Info) << "Gamepad keyboard: off (the launcher's switch)";
                return off;
            }();
            return off;
        }

        int64_t now()
        {
            return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch())
                .count();
        }

        MyGUI::EditBox* focusedEditBox()
        {
            if (!MyGUI::InputManager::getInstancePtr())
                return nullptr;
            MyGUI::Widget* widget = MyGUI::InputManager::getInstance().getKeyFocusWidget();
            MyGUI::EditBox* edit = widget ? widget->castType<MyGUI::EditBox>(false) : nullptr;
            if (edit == nullptr || edit->getEditReadOnly() || !edit->getEnabled() || !edit->getInheritedVisible())
                return nullptr;
            return edit;
        }

        void pressAndRelease(MyGUI::KeyCode key, MyGUI::Char character)
        {
            MyGUI::InputManager::getInstance().injectKeyPress(key, character);
            MyGUI::InputManager::getInstance().injectKeyRelease(key);
        }
    }

    bool gamepadKeyboardTextFocused(float& top, float& bottom)
    {
        top = 0.f;
        bottom = 1.f;
        if (keyboardOff())
            return false;
        MyGUI::EditBox* edit = focusedEditBox();
        if (edit == nullptr)
            return false;
        // where it is on the screen, from 0 (top) to 1, for the keyboard to keep clear of it
        const float height = static_cast<float>(MyGUI::RenderManager::getInstance().getViewSize().height);
        if (height > 0.f)
        {
            const MyGUI::IntCoord box = edit->getAbsoluteCoord();
            top = static_cast<float>(box.top) / height;
            bottom = static_cast<float>(box.top + box.height) / height;
        }
        return true;
    }

    bool gamepadKeyboardType(std::string_view utf8)
    {
        if (focusedEditBox() == nullptr)
            return false;
        // UTF-8 to code points, each typed as the engine types SDL's text input (no key, the character)
        for (size_t i = 0; i < utf8.size();)
        {
            const auto byte = static_cast<unsigned char>(utf8[i]);
            uint32_t codePoint = 0;
            size_t length = 1;
            if (byte < 0x80)
                codePoint = byte;
            else if ((byte & 0xe0) == 0xc0)
                codePoint = byte & 0x1f, length = 2;
            else if ((byte & 0xf0) == 0xe0)
                codePoint = byte & 0x0f, length = 3;
            else if ((byte & 0xf8) == 0xf0)
                codePoint = byte & 0x07, length = 4;
            else
            {
                ++i;
                continue;
            }
            if (i + length > utf8.size())
                break;
            for (size_t k = 1; k < length; ++k)
                codePoint = (codePoint << 6) | (static_cast<unsigned char>(utf8[i + k]) & 0x3f);
            i += length;
            if (codePoint >= 0x20)
                pressAndRelease(MyGUI::KeyCode::None, codePoint);
        }
        return true;
    }

    bool gamepadKeyboardPress(std::string_view key)
    {
        if (focusedEditBox() == nullptr)
            return false;
        MyGUI::KeyCode code = MyGUI::KeyCode::None;
        if (key == "Backspace")
            code = MyGUI::KeyCode::Backspace;
        else if (key == "Delete")
            code = MyGUI::KeyCode::Delete;
        else if (key == "Return")
            code = MyGUI::KeyCode::Return;
        else if (key == "Left")
            code = MyGUI::KeyCode::ArrowLeft;
        else if (key == "Right")
            code = MyGUI::KeyCode::ArrowRight;
        else if (key == "Up")
            code = MyGUI::KeyCode::ArrowUp;
        else if (key == "Down")
            code = MyGUI::KeyCode::ArrowDown;
        else if (key == "Home")
            code = MyGUI::KeyCode::Home;
        else if (key == "End")
            code = MyGUI::KeyCode::End;
        else if (key == "Tab")
            code = MyGUI::KeyCode::Tab;
        else
            return false;
        pressAndRelease(code, 0);
        return true;
    }

    void gamepadKeyboardCapture()
    {
        if (keyboardOff())
            return;
        sCapturedUntil = now() + sCaptureHold.count();
    }

    bool gamepadCaptured()
    {
        return now() < sCapturedUntil.load();
    }

    bool gamepadConsoleAllowed()
    {
        // as the console key: not over a modal window (a message box, a dialogue's choice)
        return !keyboardOff() && MyGUI::InputManager::getInstancePtr() && !MyGUI::InputManager::getInstance().isModalAny();
    }
}
