# Alpha3

I'm not motivated by money and wasn't going to add this but I've been asked so many times I decided to add it.
[buy me a coffee](https://ko-fi.com/jareddavenport)


A high-performance, feature-rich android launcher supporting multiple game engines including OpenMW, MegaMOD (UQM) and Dethrace, focused on native integration and modern haptic feedback. This project extends the core engines with specialized Android-first features and critical stability fixes for mobile GPUs.

## 🚀 Key Project Improvements

Since the inception of this branch, we have implemented several core architectural changes to improve the player experience:

### 1. Native Haptic Feedback (Vibration)
- **Engine-Integrated Haptics**: Unlike standard controller rumble, we've integrated native Android `Vibrator` support directly into gameplay.
- **Sound-Triggered Feedback**: Added a real-time sound monitor that analyzes playing sound files (e.g., "hit", "swing", "spell") and triggers synchronized haptic pulses via a centralized bridge.
- **Contextual Vibration**: Optimized vibration strength and duration based on game events, providing tactile feedback for combat and world interactions.

### 2. UI & Control Enhancements
- **Real-Time Cursor Tracking**: Implemented an `AutoMouseMode` callback using the Android `Choreographer` to track cursor visibility every frame.
- **Advanced UI Customization Engine**:
    - **Dynamic Controls**: A high-performance Compose-based overlay featuring fully resizable and repositionable buttons and thumbsticks.
    - **Deep Parameter Control**: Every virtual element supports detailed visual and functional tuning, including:
        - **Visuals**: Customizable colors (hex strings), alpha transparency, and support for custom icon URIs.
        - **Mapping**: Flexible assignment of Android/Keyboard keycodes and specialized mouse button emulation.
        - **Haptics**: Individual toggle for vibration feedback on a per-button basis.
        - **Locking**: Ability to lock specific buttons in place to prevent accidental movement during gameplay.
    - **Dual Thumbstick Logic**: Supports independent scaling, positioning, and deadzone configuration for movement and camera sticks.
    - **JSON Persistence**: All UI settings are serialized to a human-readable `button_configs.json` file.
        - **Path**: Located in the user's game directory: `Alpha3/[GameName]/ui/button_configs.json`.
        - **Schema**: Stores critical state data such as `keyCode`, `size`, `offsetX/Y`, `alpha`, `color`, and `mouseButton` mapping.
        - **Game-Specific Profiles**: Seamlessly switches between different control layouts when launching OpenMW, UQM, or Dethrace.
    - **Live Edit Mode**: Built-in visual editor allows players to drag, scale, and delete UI elements in real-time, providing immediate visual feedback.

### 3. Robust Asset Management
- **Smart Resource Copying**: Implemented a "version stamp" system to automatically update game resources and `defaults.bin` only when the build changes, drastically reducing first-launch times.
- **Nomedia Integration**: Automatic creation of `.nomedia` files to keep game assets from cluttering the Android Gallery.

---

## 🏗 Build & Path Reference

### 1. Build Environment
- **Standard Build**: Open the project in Android Studio and run the `app` module. Gradle manages the CMake invocation and NDK compilation.
- **NDK Version**: Recommended NDK 26+ (currently building with 29 in some environments).

### 2. File Locations & Artifacts
- **C++ Build Directory**: All native compilation occurs under the hidden Gradle cache:
  `app/.cxx/Debug/[hash]/[abi]/`
- **Dependency Installation**: External dependencies (SDL2, OpenAL, Boost) are compiled and installed into a prefix directory within the build folder:
  `app/.cxx/Debug/[hash]/[abi]/prefix/`
  *This contains the `/include` and `/lib` folders required for linking.*
- **Shared Libraries**: The final `.so` engine libraries are staged in:
  `app/build/intermediates/cmake/debug/obj/[abi]/`
- **Source Patches**: Our modifications to the original OpenMW source are maintained as patch files in:
  `app/src/main/cpp/openmw/patches/`

### 3. Asset Pathing
Native shaders and configuration templates are staged from:
- `app/src/main/assets/libopenmw/`

---

## 🛠 Technical Stack
- **Core**: OpenMW (C++)
- **Frontend**: Kotlin / Jetpack Compose
- **Graphics Wrapper**: Custom `ng-gl4es` for OpenGL to GLES translation.
- **Build System**: CMake with ExternalProject integration.

## 📦 Sub-Projects Integrated
- **OpenMW**: The main game engine.
- **Ur-Quan Masters (UQM)**: Integrated via shared linking paths.
- **Dethrace**: Included as an external project with full NDK support.

## 🏗 Build Requirements
- Android SDK 34+
- NDK 26+
- CMake 3.22.1+

---
*Alpha3 is a community-driven effort to bring the best possible native code gaming experience to mobile devices. Sisah is largely responsible for many of the improvements with OpenMW.*
