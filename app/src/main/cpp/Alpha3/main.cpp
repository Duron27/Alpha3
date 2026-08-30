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
    // signal handler
    struct sigaction sig_action{};
    sig_action.sa_sigaction = [](int signal, siginfo_t* info, void* ctx) {
        dump_register(signal, info, ctx);
        dump_stack(2);
        exit(signal);
    };
    sigemptyset(&sig_action.sa_mask);
    sig_action.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sig_action, nullptr);
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
        std::string logPath = g_java->getExternalStoragePath(env) + "/Alpha3/config/native_crash.log";
        auto file_sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>(logPath);

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
