import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.gradle.kotlin.dsl.coreLibraryDesugaring
import java.util.Properties
import java.util.Random
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "2.2.0"
    id("com.google.devtools.ksp") version "2.3.9"
    id("com.google.dagger.hilt.android")
    idea
}

idea {
    module {
        excludeDirs.add(file(".cxx"))
    }
}

val keystoreProperties = Properties()
// In the repo root, or next to it (keys kept outside the checkout); storeFile is relative to the properties file
val keystorePropertiesFile = listOf(rootProject.file("release-keystore.properties"), rootProject.file("../release-keystore.properties"))
    .firstOrNull { it.exists() } ?: rootProject.file("release-keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "org.openmw"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    buildToolsVersion = "36.0.0"

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            storeFile = keystoreProperties.getProperty("storeFile")?.let { keystorePropertiesFile.parentFile.resolve(it) }
            storePassword = keystoreProperties.getProperty("storePassword")
        }
    }

    defaultConfig {
        applicationId = "com.alpha3.launcher"
        minSdk = 26

        //noinspection OldTargetApi
        targetSdk = 36
        // Android refuses an update whose versionCode is lower than the installed one: the number of commits only goes up
        // (minutes since 2024 where there is no git history)
        versionCode = runCatching {
            providers.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText.get().trim().toInt()
        }.getOrElse { ((System.currentTimeMillis() / 60000L) - 28_401_120L).toInt() }
        versionName = runCatching {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
        }.getOrDefault("dev")  // not a git checkout, or no commits yet

        buildConfigField("boolean", "OPENMW_FORK", "${project.findProperty("ALPHA3_OPENMW_FORK") == "ON"}")
        buildConfigField("int", "RANDOMIZER", "${Random().nextInt(999).let { if (it < 0) -it else it }}")

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DOPENMW_SRC=${project.findProperty("OPENMW_SRC") ?: ""}",
                    // On by default: applies cpp/openmw/patches/multiplayer and ships the mod. -PALPHA3_MULTIPLAYER=OFF
                    // builds plain OpenMW.
                    "-DALPHA3_MULTIPLAYER=${project.findProperty("ALPHA3_MULTIPLAYER") ?: "ON"}",
                    // Also builds stock OpenMW (cpp/openmw-upstream, libopenmw_upstream.so); -PALPHA3_OPENMW_UPSTREAM=OFF skips it.
                    "-DALPHA3_OPENMW_UPSTREAM=${project.findProperty("ALPHA3_OPENMW_UPSTREAM") ?: "ON"}",
                    // Off by default: stock OpenMW only. -PALPHA3_OPENMW_FORK=ON also builds the clockwork-alpha fork (libopenmw.so).
                    "-DALPHA3_OPENMW_FORK=${project.findProperty("ALPHA3_OPENMW_FORK") ?: "OFF"}"
                )
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
    }

    androidComponents.onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }

        // Debug symbols of the native libraries, kept apart from the stripped ones the APK carries. Every library is
        // known by its build id (the same one the crash reports print):
        //  * src/main/assets/symbols/<build id>.syms  function names, small: bundled so the in-app crash viewer can name
        //    the frames of a crash. Releases will keep these online instead.
        //  * native-symbols/.build-id/ab/cdef....debug  the full debug info (source lines, inlining), for llvm-addr2line and
        //    debuggers. The newest few are kept.
        val sdkComponents = androidComponents.sdkComponents
        val generateSymbols = tasks.register("generateNativeSymbols$cap") {
            group = "build"
            description = "Writes the symbol files of the $cap native libraries, named by build id"
            dependsOn("externalNativeBuild$cap")
            val libraryRoots = listOf(file("src/main/jniLibs"), file("build/intermediates/cxx/$cap"))
            val syms = file("src/main/assets/symbols")
            val store = file("native-symbols")
            val ndk = sdkComponents.ndkDirectory
            doLast {
                val host = System.getProperty("os.name").lowercase().let {
                    when { "mac" in it -> "darwin-x86_64"; "win" in it -> "windows-x86_64"; else -> "linux-x86_64" }
                }
                val exe = if ("win" in host) ".exe" else ""
                val bin = File(ndk.get().asFile, "toolchains/llvm/prebuilt/$host/bin")
                fun tool(name: String) = File(bin, name + exe).absolutePath
                fun output(vararg command: String): String? {
                    val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val text = process.inputStream.bufferedReader().readText()
                    return if (process.waitFor() == 0) text else null
                }
                syms.mkdirs()
                val done = HashSet<String>()
                libraryRoots.filter { it.exists() }
                    .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "so" }.toList() }
                    .forEach { library ->
                        val sections = output(tool("llvm-readelf"), "-S", "-W", library.absolutePath) ?: return@forEach
                        if (".symtab" !in sections) return@forEach  // stripped: nothing to keep
                        val id = Regex("Build ID: ([0-9a-f]+)").find(
                            output(tool("llvm-readelf"), "-n", library.absolutePath) ?: ""
                        )?.groupValues?.get(1) ?: return@forEach
                        if (!done.add(id)) return@forEach

                        val table = File(syms, "$id.syms")
                        if (!table.exists()) {
                            val listing = output(tool("llvm-nm"), "--defined-only", "-S", "-C", library.absolutePath)
                                ?: return@forEach
                            val functions = ArrayList<Triple<Long, Long, String>>()
                            for (line in listing.lineSequence()) {
                                // "<address> <size> <type> <name>", the size missing for some symbols
                                val parts = line.split(' ', limit = 4)
                                val sized = parts.size == 4 && parts[2].length == 1
                                val type = if (sized) parts[2] else parts.getOrNull(1) ?: continue
                                if (type.length != 1 || type[0] !in "TtWwi") continue
                                val address = parts[0].toLongOrNull(16) ?: continue
                                val size = if (sized) parts[1].toLongOrNull(16) ?: 0L else 0L
                                val name = if (sized) parts[3] else parts.drop(2).joinToString(" ")
                                functions += Triple(address, size, name)
                            }
                            functions.sortBy { it.first }
                            table.bufferedWriter().use { out ->
                                out.write("# alpha3 symbols 1\n# build-id $id\n# module ${library.name}\n")
                                for ((address, size, name) in functions) {
                                    out.write("${address.toString(16)} ${size.toString(16)} $name\n")
                                }
                            }
                            logger.lifecycle("Symbols: ${library.name} -> ${table.name} (${functions.size} functions)")
                        }

                        val debug = File(store, ".build-id/${id.take(2)}/${id.drop(2)}.debug")
                        if (!debug.exists() && ".debug_info" in sections) {
                            debug.parentFile.mkdirs()
                            val copy = ProcessBuilder(
                                tool("llvm-objcopy"), "--only-keep-debug", library.absolutePath, debug.absolutePath
                            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                            if (copy.waitFor() == 0) {
                                logger.lifecycle("Debug info: ${library.name} -> ${debug.relativeTo(projectDir)}")
                            } else {
                                debug.delete()
                            }
                        }
                    }
                // The full debug files are big and every build makes new ones: keep the newest three of each library
                val index = File(store, "index.tsv")
                val known = LinkedHashMap<String, Pair<String, Long>>()  // build id -> library, time
                if (index.exists()) {
                    index.readLines().forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size == 3) known[parts[0]] = parts[1] to (parts[2].toLongOrNull() ?: 0L)
                    }
                }
                for (id in done) {
                    val debug = File(store, ".build-id/${id.take(2)}/${id.drop(2)}.debug")
                    if (debug.exists() && id !in known) {
                        val name = libraryRoots.filter { it.exists() }.firstNotNullOfOrNull { root ->
                            root.walkTopDown().firstOrNull { it.isFile && it.extension == "so" &&
                                output(tool("llvm-readelf"), "-n", it.absolutePath)?.contains(id) == true }?.name
                        } ?: "?"
                        known[id] = name to System.currentTimeMillis()
                    }
                }
                known.entries.groupBy { it.value.first }.values.forEach { generations ->
                    generations.sortedByDescending { it.value.second }.drop(3).forEach { stale ->
                        File(store, ".build-id/${stale.key.take(2)}/${stale.key.drop(2)}.debug").delete()
                        known.remove(stale.key)
                    }
                }
                store.mkdirs()
                index.writeText(known.entries.joinToString("\n") { "${it.key}\t${it.value.first}\t${it.value.second}" } + "\n")
            }
        }

        tasks.withType<MergeSourceSetFolders>().configureEach {
            if (name == "merge${cap}Assets") {
                dependsOn("externalNativeBuild$cap")
                dependsOn(generateSymbols)
                inputs.dir(file("src/main/assets"))
            }
        }
    }

    // The .syms tables in assets/symbols are gigabytes: they stay out of the APK unless -PALPHA3_BUNDLE_SYMBOLS=ON (the files
    // are still written to src/main/assets/symbols and native-symbols, so crash reports can be symbolized offline).
    androidResources {
        // Only the languages the app ships; libraries bring dozens more into resources.arsc
        localeFilters += listOf("en", "ru", "zh-rCN")
        if (project.findProperty("ALPHA3_BUNDLE_SYMBOLS") != "ON") {
            ignoreAssetsPatterns.add("!symbols")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../cpp/CMakeLists.txt")  // shared with iOS
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            /*isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro"
            )
            isJniDebuggable = false*/
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.2.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Not needed at run time: licence/build notes, test leftovers and BouncyCastle's post-quantum data tables
            excludes += listOf(
                "/META-INF/*.version", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST", "/META-INF/maven/**", "/META-INF/proguard/**",
                "/DebugProbesKt.bin", "/LICENSE-junit.txt", "/junit/**",
                "/org/bouncycastle/pqc/**/*.properties", "/org/bouncycastle/pqc/**/*.bin"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            //keepDebugSymbols += "**/*.so"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
            // The mod downloader's native tools (cpp/modtools: LOOT, Wabbajack), built by buildModTools below
            jniLibs.directories.add(layout.buildDirectory.dir("modtools/jniLibs").get().asFile.path)
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // shared KMP code (iOS + Android)
    implementation(projects.composeApp)
    // Android Frame Pacing (Swappy): its Java half; the native half is fetched by cpp/CMakeLists.txt (keep the versions equal)
    implementation("androidx.games:games-frame-pacing:2.1.3")

    // androidx/ktx
    implementation(libs.core.ktx)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.browser:browser:1.8.0") // Custom Tabs for the Nexus free-member download flow
    // the terminal (utils/Terminal.kt): a shell in a pseudo-terminal, drawn by an xterm emulator (Apache 2.0)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.runner)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.foundation)
    coreLibraryDesugaring(libs.desugar.jdk.libs) // support some java8 features

    // compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.reorderable)
    implementation(libs.colorpicker.compose)

    // compose adaptive
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation)

    // Hilt injector
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // data store
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)

    // web/parse
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.protobuf.javalite)
    implementation(libs.guava)

    // lazy to organize XD
    implementation(libs.relinker)
    implementation(libs.androidx.window)
    implementation(libs.animate.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.core)
    implementation(libs.org.eclipse.jgit)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.accompanist.pager)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.x.zip.jbinding.xandroid)
    implementation(libs.bcprov.jdk18on)

    // rooms
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.test.manifest)
}

// The mod downloader's native tools: cpp/modtools is a small Rust library with LOOT's load order sorting (libloot,
// GPL-3.0) and the reading of Wabbajack lists, built with cargo for arm64 and packaged with the other native libraries
// as libalpha3_modtools.so. Needs Rust with the aarch64-linux-android target (rustup target add aarch64-linux-android);
// -PALPHA3_MODTOOLS=OFF leaves it out: the organizer then only sorts by masters, and Wabbajack lists cannot install.
val modToolsEnabled = project.findProperty("ALPHA3_MODTOOLS") != "OFF"
val buildModTools = tasks.register("buildModTools") {
    group = "build"
    description = "Builds cpp/modtools (libloot, Wabbajack plans) for arm64 Android"
    val crate = rootProject.file("cpp/modtools")
    val target = layout.buildDirectory.dir("modtools/target").get().asFile
    val out = layout.buildDirectory.dir("modtools/jniLibs/arm64-v8a").get().asFile
    val ndk = androidComponents.sdkComponents.ndkDirectory
    val minSdk = android.defaultConfig.minSdk ?: 26
    inputs.dir(File(crate, "src"))
    inputs.files(File(crate, "Cargo.toml"), File(crate, "Cargo.lock"))
    outputs.dir(out)
    onlyIf { modToolsEnabled }
    doLast {
        val host = System.getProperty("os.name").lowercase().let {
            when { "mac" in it -> "darwin-x86_64"; "win" in it -> "windows-x86_64"; else -> "linux-x86_64" }
        }
        val bin = File(ndk.get().asFile, "toolchains/llvm/prebuilt/$host/bin")
        val clang = File(bin, "aarch64-linux-android$minSdk-clang").path
        val cargo = listOf(File(System.getProperty("user.home"), ".cargo/bin/cargo")).firstOrNull { it.isFile }?.path ?: "cargo"
        val process = ProcessBuilder(cargo, "build", "--release", "--locked", "--target", "aarch64-linux-android",
            "--target-dir", target.path)
            .directory(crate)
            .redirectErrorStream(true)
            .apply {
                environment()["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = clang
                environment()["CC_aarch64_linux_android"] = clang
                environment()["AR_aarch64_linux_android"] = File(bin, "llvm-ar").path
                // 16 KB pages (Android 15 devices), like the CMake libraries
                environment()["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
            }
            .start()
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("cargo build of cpp/modtools failed:\n$log")
        out.mkdirs()
        File(target, "aarch64-linux-android/release/libalpha3_modtools.so").copyTo(File(out, "libalpha3_modtools.so"), overwrite = true)
    }
}
tasks.named("preBuild") { dependsOn(buildModTools) }

