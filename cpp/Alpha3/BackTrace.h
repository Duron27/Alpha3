#pragma once

#include <cxxabi.h>
#include <unwind.h>
#include <array>
#include <dlfcn.h>
#include <csignal>
#include <asm-generic/siginfo.h>

void dump_register(int signal, siginfo_t* info, void* ctx);
void dump_stack(int ignoreDepth = 0, int maxDepth = 31);

/// Where OpenMW writes openmw.log and renderer.status (e.g. /storage/emulated/0/Alpha3/config). The crash report
/// quotes them.
void crash_set_log_dir(const char* dir);

/// A line the report starts with (app version, build time).
void crash_set_app_info(const char* text);

/// Installs the handlers for SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL and SIGTRAP and the std::terminate handler.
void crash_install();
