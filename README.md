# OpenMW-Android (Alpha3)

A high-performance, feature-rich Android port of the OpenMW engine, focused on native integration and modern haptic feedback. This project extends the core engine with specialized Android-first features and critical stability fixes for mobile GPUs.

## 🚀 Key Project Improvements

Since the inception of this branch, we have implemented several core architectural changes to improve the player experience and engine stability on Android:

### 1. Advanced JNI Bridge & Management
- **Async Messaging**: Static JNI callbacks for sound and gameplay events ensure thread-safe communication between the engine thread and the Android main thread.

### 2. Native Haptic Feedback (Vibration)
- **Engine-Integrated Haptics**: Unlike standard controller rumble, we've integrated native Android `Vibrator` support directly into gameplay.
- **Sound-Triggered Feedback**: Added a real-time sound monitor that analyzes playing sound files (e.g., "hit", "swing", "spell") and triggers synchronized haptic pulses.
- **Contextual Vibration**: Optimized vibration strength and duration based on game events, providing tactile feedback for combat and world interactions.

### 3. UI & Control Enhancements
- **Real-Time Cursor Tracking**: Implemented an `AutoMouseMode` callback using the Android `Choreographer` to track cursor visibility every frame.
- **Dynamic Input System**: Native integration of touch-to-keyboard mapping with configurable vibration-on-press settings for every virtual button.

### 4. Robust Asset Management
- **Smart Resource Copying**: Implemented a "version stamp" system to automatically update game resources and `defaults.bin` only when the build changes, drastically reducing first-launch times.
- **Nomedia Integration**: Automatic creation of `.nomedia` files to keep game assets from cluttering the Android Gallery.

---

## 🛠 Technical Stack
- **Core**: OpenMW (C++)
- **Frontend**: Kotlin / Jetpack Compose
- **Graphics Wrapper**: Custom `ng-gl4es` for OpenGL to GLES translation.
- **Build System**: CMake with ExternalProject integration for dependencies like SDL2, OpenAL, and Boost.

## 📦 Sub-Projects Integrated
- **OpenMW**: The main game engine.
- **Ur-Quan Masters (UQM)**: Integrated via shared linking paths in the build system.
- **Dethrace**: Included as an external project with full NDK support.

## 🏗 Build Requirements
- Android SDK 34+
- NDK 29+
- CMake 3.22.1+

---
*OpenMW-Android Alpha3 is a community-driven effort to bring the best possible Morrowind experience to mobile devices. Sisah is larely responsible for many of the Improvements. *
