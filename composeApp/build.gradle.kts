import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9 no longer allows com.android.application next to the multiplatform plugin, so the
    // Android side of this module is a library. The Android app itself lives in :androidApp.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    // Room's code generator for the mod downloader's database (version from the root build)
    id("com.google.devtools.ksp")
}

// Provide defaults for Xcode properties to avoid "no value available" errors
// This is necessary because the Compose plugin tries to read these even when not building from Xcode
project.extra.set("kotlin.native.apple.archs", project.findProperty("kotlin.native.apple.archs") ?: "arm64")
project.extra.set("kotlin.native.apple.target", project.findProperty("kotlin.native.apple.target") ?: "iphoneos")
project.extra.set("kotlin.native.apple.configuration", project.findProperty("kotlin.native.apple.configuration") ?: "Debug")

// Targeted fix for the SyncComposeResourcesForIosTask crash
tasks.matching { it.name.contains("syncComposeResources", ignoreCase = true) }.configureEach {
    try {
        val getXcodeTargetArchs = this.javaClass.getMethod("getXcodeTargetArchs")
        val archs = getXcodeTargetArchs.invoke(this) as? org.gradle.api.provider.ListProperty<String>
        archs?.convention(listOf("arm64"))
    } catch (e: Exception) {
        // Fallback: if we can't set the convention, just disable the task
        enabled = false
    }
}

kotlin {
    androidLibrary {
        namespace = "org.alpha3.launcher"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // The system libarchive (7z, rar, zip) unpacks downloaded mods. The SDK has the library but not its headers,
        // so the .def declares the few functions used; the app links -larchive (see iosApp's OTHER_LDFLAGS).
        iosTarget.compilations.getByName("main").cinterops.create("libarchive") {
            defFile(project.file("src/nativeInterop/cinterop/libarchive.def"))
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.documentfile)
            implementation(libs.relinker)
            implementation(libs.coil3.gif)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.x.zip.jbinding.xandroid)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonMain.dependencies {
            implementation(libs.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            api(compose.components.resources)
            implementation(compose.preview)
            implementation(libs.compose.icons.extended)
            implementation(libs.reorderable)
            implementation(libs.colorpicker.kmp)
            implementation(libs.kotlinx.serialization.json.kmp)
            implementation(libs.jb.adaptive)
            implementation(libs.jb.adaptive.layout)
            implementation(libs.jb.adaptive.navigation)
            implementation(libs.jb.adaptive.navigation.suite)
            implementation(libs.jb.navigation.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.filekit.coil)
            api(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
            implementation(libs.okio)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.room.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach { add(it, libs.androidx.room.compiler) }
}

compose.resources {
    // public so :androidApp (and later code) can use Res.string / Res.drawable
    publicResClass = true
    packageOfResClass = "org.alpha3.launcher.resources"
}

tasks.register("printIosDeviceInfo") {

    group = "help"
    description = "Prints information about connected iOS Simulators and physical devices"
    doLast {
        val bundleId = "org.alpha3.launcher.Alpha3"
        
        println("\n--- iOS Simulator ---")
        val simCmd = "xcrun simctl list devices | grep '(Booted)' | head -1 | grep -oE '[0-9A-F-]{36}'"
        val simId = try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", simCmd))
            process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "" }

        if (simId.isNotEmpty()) {
            val pathCmd = "xcrun simctl get_app_container $simId $bundleId data"
            val path = try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", pathCmd))
                process.inputStream.bufferedReader().readText().trim()
            } catch (e: Exception) { "" }
            
            if (path.isNotEmpty()) {
                println("Booted Simulator ID: $simId")
                println("App Documents Path: $path/Documents")
                println("App Support Path:   $path/Library/Application Support/Alpha3")
                println("Resources Path:     $path/Library/Application Support/Alpha3/resources")
                println("\nTo open Resources in Finder run:")
                println("open \"$path/Library/Application Support/Alpha3/resources\"")
            } else {
                println("Booted Simulator: $simId (App not installed)")
            }
        } else {
            println("No booted iOS Simulator found.")
        }

        println("\n--- Physical iOS Devices ---")
        val deviceCmd = "xcrun devicectl list devices --hide-headers --columns identifier,model,name"
        val devices = try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", deviceCmd))
            process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "" }

        if (devices.isNotEmpty() && !devices.contains("No devices found")) {
            println(devices)
            println("\nTo install on device, use:")
            println("buildscripts/install_on_device.sh")
        } else {
            println("No physical iOS devices detected via USB/Network.")
        }
        println("")
    }
}


// ---------------------------------------------------------------------------------------------
// Native iOS libraries (vcpkg deps + OpenMW + MoltenVK) built by cpp/CMakeLists.txt.
// Frameworks end up in iosApp/EmbeddedLibsDevice or iosApp/EmbeddedLibsSim.
//
// Runs as part of Android Studio's Build button (hooked into androidPreBuild) on macOS.
//   -PnativeIos=false                 skip it
//   -PnativeIosPlatform=ios           device only (default) | ios-simulator | both
// ---------------------------------------------------------------------------------------------
val cppDir = rootProject.layout.projectDirectory.dir("cpp").asFile
val nativeIosEnabled = org.gradle.internal.os.OperatingSystem.current().isMacOsX &&
    (findProperty("nativeIos")?.toString() != "false")
val nativeIosPlatform = findProperty("nativeIosPlatform")?.toString() ?: "ios"

// Gradle launched from Android Studio doesn't inherit the shell PATH, so resolve cmake explicitly
val cmakeExe = listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/Applications/CMake.app/Contents/bin/cmake")
    .firstOrNull { File(it).canExecute() } ?: "cmake"
val nativeToolPath = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

// Xcode exports its own SDK settings to the run-script phase that starts Gradle. They must not reach the native
// build: with SDKROOT pointing at the iPhoneOS SDK, vcpkg's host tools (ffmpeg's ops_asmgen, ...) get compiled as
// iOS executables that macOS refuses to run. The toolchain passes the SDK it wants explicitly.
val xcodeEnvVars = listOf(
    "SDKROOT", "PLATFORM_NAME", "SDK_NAME", "EFFECTIVE_PLATFORM_NAME",
    "IPHONEOS_DEPLOYMENT_TARGET", "MACOSX_DEPLOYMENT_TARGET", "LLVM_TARGET_TRIPLE_SUFFIX",
)

fun registerNativeIos(taskName: String, sysroot: String, buildDirName: String): TaskProvider<Task> {
    val buildDir = layout.buildDirectory.dir(buildDirName).get().asFile.absolutePath
    val configure = tasks.register<Exec>("${taskName}Configure") {
        group = "native"
        workingDir = cppDir
        environment("PATH", nativeToolPath)
        xcodeEnvVars.forEach { environment.remove(it) }
        commandLine(
            cmakeExe, "-S", cppDir.absolutePath, "-B", buildDir,
            "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=$sysroot", "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-Wno-dev"
        )
    }
    val build = tasks.register<Exec>("${taskName}Build") {
        group = "native"
        dependsOn(configure)
        workingDir = cppDir
        environment("PATH", nativeToolPath)
        xcodeEnvVars.forEach { environment.remove(it) }
        commandLine(cmakeExe, "--build", buildDir, "-j")
    }
    return tasks.register(taskName) {
        group = "native"
        description = "Builds the iOS native libraries ($sysroot) with CMake"
        dependsOn(build)
    }
}

val buildNativeIosDevice = registerNativeIos("buildNativeIosDevice", "iphoneos", "cmake-ios")
val buildNativeIosSimulator = registerNativeIos("buildNativeIosSimulator", "iphonesimulator", "cmake-ios-sim")

val buildNativeIos = tasks.register("buildNativeIos") {
    group = "native"
    description = "Builds the iOS native libraries per -PnativeIosPlatform"
    if (nativeIosPlatform == "ios" || nativeIosPlatform == "both") dependsOn(buildNativeIosDevice)
    if (nativeIosPlatform == "ios-simulator" || nativeIosPlatform == "both") dependsOn(buildNativeIosSimulator)
}

// Default control layout + icons: composeApp/UI is the single source (Android installs it on first run);
// mirror it into the iOS bundle's OpenMWAssets on every Xcode build.
val syncIosUiAssets = tasks.register<Sync>("syncIosUiAssets") {
    group = "native"
    from(rootProject.layout.projectDirectory.dir("composeApp/UI"))
    into(rootProject.layout.projectDirectory.dir("iosApp/OpenMWAssets/ui"))
}
tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach { dependsOn(syncIosUiAssets) }

if (nativeIosEnabled) {
    tasks.matching { it.name == "androidPreBuild" }.configureEach { dependsOn(buildNativeIos) }

    // The Run button (Xcode iosApp config) only invokes embedAndSignAppleFrameworkForXcode via the
    // Xcode build phase, so hook the native build in there too, matching the platform Xcode targets.
    val xcodePlatform = findProperty("kotlin.native.apple.target")?.toString()
        ?: System.getenv("PLATFORM_NAME")
    tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach {
        dependsOn(if (xcodePlatform == "iphonesimulator") buildNativeIosSimulator else buildNativeIosDevice)
    }
}
