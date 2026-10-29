#include "main.h"
#include "spdlog/sinks/android_sink.h"
#include "BackTrace.h"
#include "deps/Obfuscator/obfuscator.h"
#include "spdlog/sinks/basic_file_sink.h"
#include <stdlib.h>
#include <dlfcn.h>
#include <vector>
#include <string>


JavaVM* g_java_vm = nullptr;
Java* g_java = nullptr;

void initCrashDump()
{
    // SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP and std::terminate: see BackTrace.cpp
    crash_install();
}

/**
 * init spdlog
 * @param env
 * @return
 */
int initSpdLog(JNIEnv* env) {
    try {
        // android sink (print to logcat)
        auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>(LOG_TAG);

        // file sink (save to file)
        const std::string configDir = g_java->getExternalStoragePath(env) + "/Alpha3/config";
        crash_set_log_dir(configDir.c_str());
        std::string logPath = configDir + "/native_crash.log";
        auto file_sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>(logPath, true);  // truncate: one launch per file

        std::vector<spdlog::sink_ptr> sinks { android_sink, file_sink };
        auto logger = std::make_shared<spdlog::logger>("multi_logger", sinks.begin(), sinks.end());
        logger->set_level(spdlog::level::info);
        logger->set_pattern("[%Y-%m-%d %H:%M:%S] [%^%l%$] %v");

        spdlog::set_default_logger(logger);
        spdlog::flush_on(spdlog::level::info);
        spdlog::info("Logger initialized successfully");
        return 1;
    }
    catch (const spdlog::spdlog_ex& ex) {
        LOGE("Log initialization failed: %s", ex.what());
        return 0;
    }
}

jint JNI_OnLoad(JavaVM* vm, [[maybe_unused]] void* reserved)
{
	JNIEnv* env;
	if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
		return JNI_ERR;
	}
	g_java_vm = vm;

    g_java = new Java(g_java_vm, env);
    if (g_java->getVersion().empty()) {
        return env->GetVersion();
    }

    initCrashDump();
    if (!initSpdLog(env)) {
        return env->GetVersion();
    }

    spdlog::info("Alpha3 version: {}", g_java->getVersion());
    {
        const std::string info = g_java->getVersion() + ", built " __DATE__ " " __TIME__;
        crash_set_app_info(info.c_str());
    }

    spdlog::info("Alpha3 library loaded! Build time: " __DATE__ " " __TIME__);
	return env->GetVersion();
}

void JNI_OnUnload([[maybe_unused]] JavaVM* vm, [[maybe_unused]] void* reserved)
{
    spdlog::info("Alpha3 library unloaded!");
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_EngineActivity_initAlpha3(JNIEnv* env, jobject obj)
{
    g_java->setupContext(obj, env);
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_EngineActivity_setNativeEnv(JNIEnv* env, jobject /* thiz */, jstring key, jstring value)
{
    const char* nativeKey = env->GetStringUTFChars(key, nullptr);
    const char* nativeValue = env->GetStringUTFChars(value, nullptr);

    if (nativeKey != nullptr && nativeValue != nullptr) {
        setenv(nativeKey, nativeValue, 1);
        spdlog::info("Set native env: {}={}", nativeKey, nativeValue);
    }

    if (nativeKey != nullptr) env->ReleaseStringUTFChars(key, nativeKey);
    if (nativeValue != nullptr) env->ReleaseStringUTFChars(value, nativeValue);
}

extern "C" JNIEXPORT jint JNICALL Java_org_openmw_utils_NativeAppLauncher_launchIntMain(JNIEnv* env, jobject /* thiz */, jstring libName, jobjectArray args)
{
  const char* nativeLibName = env->GetStringUTFChars(libName, nullptr);
  spdlog::info("launchIntMain: Loading {}", nativeLibName);

  void* handle = dlopen(nativeLibName, RTLD_LAZY);
  if (!handle) {
    spdlog::error("Failed to load library {}: {}", nativeLibName, dlerror());
    env->ReleaseStringUTFChars(libName, nativeLibName);
    return -1;
  }

  typedef int (*MainFunc)(int, char**);
  MainFunc int_main = (MainFunc)dlsym(handle, "SDL_main"); // Try SDL_main first
  if (!int_main) {
    int_main = (MainFunc)dlsym(handle, "int_main");
  }

  if (!int_main) {
    spdlog::error("Failed to find int_main or SDL_main in {}", nativeLibName);
    dlclose(handle);
    env->ReleaseStringUTFChars(libName, nativeLibName);
    return -2;
  }

  int argc = env->GetArrayLength(args);
  std::vector<std::string> argv_str;
  std::vector<char*> argv;

  for (int i = 0; i < argc; ++i) {
    jstring arg = (jstring)env->GetObjectArrayElement(args, i);
    const char* nativeArg = env->GetStringUTFChars(arg, nullptr);
    argv_str.push_back(nativeArg);
    env->ReleaseStringUTFChars(arg, nativeArg);
  }

  for (int i = 0; i < argc; ++i) {
    argv.push_back(const_cast<char*>(argv_str[i].c_str()));
  }
  argv.push_back(nullptr);

  spdlog::info("Calling main in {}", nativeLibName);
  int result = int_main(argc, argv.data());
  spdlog::info("main returned {}", result);

  dlclose(handle);
  env->ReleaseStringUTFChars(libName, nativeLibName);
  return result;
}

/**
 * Runs `int symbol(int argc, char** argv, void (*output)(void*, const char*, size_t), void* user)` from a shared
 * library in this process and returns everything the call passed to `output`. The exit code is stored in
 * codeOut[0]. The library delivers output per calling thread, so calls from different threads can overlap.
 */
extern "C" JNIEXPORT jstring JNICALL Java_org_openmw_utils_NativeAppLauncher_runWithOutput(
    JNIEnv* env, jobject /* thiz */, jstring libName, jstring symbol, jobjectArray args, jintArray codeOut)
{
  auto fail = [&](const std::string& message, int code) {
    jint c = code;
    env->SetIntArrayRegion(codeOut, 0, 1, &c);
    return env->NewStringUTF(message.c_str());
  };

  const char* nativeLib = env->GetStringUTFChars(libName, nullptr);
  const char* nativeSymbol = env->GetStringUTFChars(symbol, nullptr);
  std::string lib = nativeLib, sym = nativeSymbol;
  env->ReleaseStringUTFChars(libName, nativeLib);
  env->ReleaseStringUTFChars(symbol, nativeSymbol);

  // The handle is kept open so repeated runs do not reload the library.
  void* handle = dlopen(lib.c_str(), RTLD_LAZY);
  if (!handle) return fail(std::string("Failed to load ") + lib + ": " + dlerror() + "\n", -1);

  typedef void (*OutputFunc)(void*, const char*, size_t);
  typedef int (*EntryFunc)(int, char**, OutputFunc, void*);
  EntryFunc entry = (EntryFunc)dlsym(handle, sym.c_str());
  if (!entry) return fail("Symbol " + sym + " not found in " + lib + "\n", -2);

  int argc = env->GetArrayLength(args);
  std::vector<std::string> argvStr;
  for (int i = 0; i < argc; ++i) {
    jstring arg = (jstring)env->GetObjectArrayElement(args, i);
    const char* nativeArg = env->GetStringUTFChars(arg, nullptr);
    argvStr.push_back(nativeArg);
    env->ReleaseStringUTFChars(arg, nativeArg);
    env->DeleteLocalRef(arg);
  }
  std::vector<char*> argv;
  for (auto& a : argvStr) argv.push_back(const_cast<char*>(a.c_str()));
  argv.push_back(nullptr);

  std::string output;
  int result = entry(argc, argv.data(),
      [](void* user, const char* text, size_t length) { static_cast<std::string*>(user)->append(text, length); },
      &output);

  jint c = result;
  env->SetIntArrayRegion(codeOut, 0, 1, &c);
  return env->NewStringUTF(output.c_str());
}
