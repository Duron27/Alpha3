#include "BackTrace.h"

#include <spdlog/spdlog.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <link.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <exception>
#include <string>
#include <typeinfo>

extern char** environ;

namespace
{
	// The crash report is built while the process is dying: files are read into this buffer rather than the heap.
	char g_buffer[256 * 1024];
	char g_logDir[512] = "";
	char g_appInfo[256] = "";
	std::atomic<int> g_crashing{ 0 };
	// What was installed before us: bionic's own handler, which starts debuggerd and so produces the tombstone.
	struct sigaction g_previous[NSIG];
	// The report of the latest crash only: crash_report.txt is rewritten from scratch by every crash.
	int g_reportFd = -1;
}

void crash_log(const char* format, ...)
{
	va_list args;
	va_start(args, format);

	char buffer[1024];
	vsnprintf(buffer, sizeof(buffer), format, args);

	va_end(args);

	spdlog::error("{}", buffer);

	if (g_reportFd >= 0) {
		const size_t length = strlen(buffer);
		if (write(g_reportFd, buffer, length) < 0 || write(g_reportFd, "\n", 1) < 0) {
			g_reportFd = -1;
		}
	}
}

void crash_set_app_info(const char* text)
{
	snprintf(g_appInfo, sizeof(g_appInfo), "%s", text ? text : "");
}

void crash_set_log_dir(const char* dir)
{
	snprintf(g_logDir, sizeof(g_logDir), "%s", dir ? dir : "");
}

static const char* signal_label(int signal)
{
	switch (signal) {
		case SIGSEGV: return "SIGSEGV";
		case SIGABRT: return "SIGABRT";
		case SIGBUS: return "SIGBUS";
		case SIGFPE: return "SIGFPE";
		case SIGILL: return "SIGILL";
		case SIGTRAP: return "SIGTRAP";
		case SIGKILL: return "SIGKILL";
		case SIGTERM: return "SIGTERM";
		default: return "SIG?";
	}
}

static const char* code_label(int signal, int code)
{
	if (code == SI_USER) return "SI_USER (sent by kill)";
	if (code == SI_QUEUE) return "SI_QUEUE (sent by sigqueue/abort)";
	if (code == SI_TKILL) return "SI_TKILL (sent by tgkill/raise)";
	switch (signal) {
		case SIGSEGV:
			if (code == SEGV_MAPERR) return "SEGV_MAPERR (address not mapped: null or wild pointer)";
			if (code == SEGV_ACCERR) return "SEGV_ACCERR (permission: write to read-only, or execute of data)";
			break;
		case SIGBUS:
			if (code == BUS_ADRALN) return "BUS_ADRALN (misaligned access)";
			if (code == BUS_ADRERR) return "BUS_ADRERR (no such physical address)";
			if (code == BUS_OBJERR) return "BUS_OBJERR (hardware error, e.g. truncated mmap file)";
			break;
		case SIGFPE:
			if (code == FPE_INTDIV) return "FPE_INTDIV (integer divide by zero)";
			if (code == FPE_FLTDIV) return "FPE_FLTDIV (float divide by zero)";
			break;
		case SIGILL:
			if (code == ILL_ILLOPC) return "ILL_ILLOPC (illegal instruction)";
			break;
		default: break;
	}
	return "UNKNOWN";
}

static void logDeviceInfo()
{
	char value[PROP_VALUE_MAX];
	const char* const properties[][2] = {
		{ "Device", "ro.product.model" },
		{ "Manufacturer", "ro.product.manufacturer" },
		{ "ABI", "ro.product.cpu.abi" },
		{ "Android Version", "ro.build.version.release" },
		{ "SDK", "ro.build.version.sdk" },
		{ "SoC", "ro.board.platform" },
		{ "Build", "ro.build.fingerprint" },
	};
	for (const auto& property : properties) {
		value[0] = 0;
		__system_property_get(property[1], value);
		crash_log("%s: %s", property[0], value);
	}
}

/// Prints the last `lines` lines of a file, newest last. Returns false when the file cannot be read.
static bool log_file_tail(const char* title, const char* path, int lines, const char* highlight = nullptr)
{
	const int fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		crash_log("%s: cannot open %s", title, path);
		return false;
	}
	struct stat st{};
	fstat(fd, &st);
	const off_t size = st.st_size;
	const off_t offset = size > (off_t) sizeof(g_buffer) - 1 ? size - (off_t) (sizeof(g_buffer) - 1) : 0;
	const ssize_t count = pread(fd, g_buffer, sizeof(g_buffer) - 1, offset);
	close(fd);
	if (count <= 0) {
		crash_log("%s: %s is empty", title, path);
		return true;
	}
	g_buffer[count] = 0;

	if (highlight) {
		if (const char* hit = strstr(g_buffer, highlight)) {
			const char* end = strchr(hit, '\n');
			crash_log("%s: first \"%s\" in the last %d KB: %.*s", title, highlight, (int) (count / 1024),
					end ? (int) (end - hit) : 200, hit);
		}
	}

	// Start of the last `lines` lines
	char* start = g_buffer + count;
	int seen = 0;
	if (start > g_buffer && start[-1] == '\n') {
		--start;
	}
	while (start > g_buffer) {
		if (start[-1] == '\n' && ++seen >= lines) {
			break;
		}
		--start;
	}
	crash_log("---- %s (%s, last %d lines of %lld bytes) ----", title, path, lines, (long long) size);
	for (char* line = start; line && *line;) {
		char* next = strchr(line, '\n');
		if (next) {
			*next = 0;
		}
		crash_log("\t%s", line);
		line = next ? next + 1 : nullptr;
	}
	return true;
}

static void log_process_stats()
{
	crash_log("---- Process ----");
	crash_log("\tpid %d, crashing tid %ld", (int) getpid(), (long) syscall(SYS_gettid));

	auto print_matching = [](const char* path, const char* const* prefixes, int prefixCount) {
		const int fd = open(path, O_RDONLY | O_CLOEXEC);
		if (fd < 0) {
			return;
		}
		const ssize_t count = read(fd, g_buffer, sizeof(g_buffer) - 1);
		close(fd);
		if (count <= 0) {
			return;
		}
		g_buffer[count] = 0;
		for (char* line = g_buffer; line && *line;) {
			char* next = strchr(line, '\n');
			if (next) {
				*next = 0;
			}
			for (int i = 0; i < prefixCount; ++i) {
				if (strncmp(line, prefixes[i], strlen(prefixes[i])) == 0) {
					crash_log("\t%s", line);
					break;
				}
			}
			line = next ? next + 1 : nullptr;
		}
	};
	const char* const status[] = { "VmPeak", "VmSize", "VmRSS", "VmSwap", "Threads", "Name" };
	print_matching("/proc/self/status", status, 6);
	const char* const meminfo[] = { "MemTotal", "MemAvailable", "SwapFree" };
	print_matching("/proc/meminfo", meminfo, 3);
}

static void log_threads(long crashingTid)
{
	DIR* dir = opendir("/proc/self/task");
	if (!dir) {
		return;
	}
	crash_log("---- Threads ----");
	while (dirent* entry = readdir(dir)) {
		const long tid = atol(entry->d_name);
		if (tid <= 0) {
			continue;
		}
		char path[64];
		snprintf(path, sizeof(path), "/proc/self/task/%ld/comm", tid);
		char name[64] = "?";
		if (const int fd = open(path, O_RDONLY | O_CLOEXEC); fd >= 0) {
			const ssize_t count = read(fd, name, sizeof(name) - 1);
			close(fd);
			name[count > 0 ? count : 0] = 0;
			if (char* newline = strchr(name, '\n')) {
				*newline = 0;
			}
		}
		crash_log("\ttid %ld %s%s", tid, name, tid == crashingTid ? "  <== crashed" : "");
	}
	closedir(dir);
}

/// Build id of a loaded module (the GNU note), as hex; empty when it has none. The symbol files are named by it.
static std::string module_build_id(const dl_phdr_info* info)
{
	for (int i = 0; i < info->dlpi_phnum; ++i) {
		const ElfW(Phdr)& header = info->dlpi_phdr[i];
		if (header.p_type != PT_NOTE) {
			continue;
		}
		const uint8_t* cursor = reinterpret_cast<const uint8_t*>(info->dlpi_addr + header.p_vaddr);
		const uint8_t* end = cursor + header.p_memsz;
		while (cursor + sizeof(ElfW(Nhdr)) <= end) {
			const auto* note = reinterpret_cast<const ElfW(Nhdr)*>(cursor);
			const uint8_t* name = cursor + sizeof(ElfW(Nhdr));
			const uint8_t* desc = name + ((note->n_namesz + 3) & ~3u);
			if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz == 4 && memcmp(name, "GNU", 4) == 0
					&& desc + note->n_descsz <= end) {
				std::string id;
				for (uint32_t b = 0; b < note->n_descsz; ++b) {
					char hex[3];
					snprintf(hex, sizeof(hex), "%02x", desc[b]);
					id += hex;
				}
				return id;
			}
			cursor = desc + ((note->n_descsz + 3) & ~3u);
		}
	}
	return {};
}

/// Every loaded module with its load base and build id: "module <base> <build-id> <path>". A frame in the report is
/// "<path> + 0x<offset from that base>"; the build id picks the matching symbol file.
static void log_modules()
{
	crash_log("---- Loaded modules (load base, build id, path) ----");
	dl_iterate_phdr(
			[](dl_phdr_info* info, size_t, void*) -> int {
				if (!info->dlpi_name || info->dlpi_name[0] != '/') {
					return 0;
				}
				const std::string id = module_build_id(info);
				crash_log("module 0x%llx %s %s", (unsigned long long) info->dlpi_addr, id.empty() ? "-" : id.c_str(),
						info->dlpi_name);
				return 0;
			},
			nullptr);
}

static void log_environment()
{
	static const char* const prefixes[] = { "OSG_", "OPENMW_", "VK_", "MESA", "ZINK", "TU_", "ADRENOTOOLS", "LD_", "SDL_",
		"ALPHA3", "LIBGL", "GALLIUM", "EGL_", "ANGLE" };
	crash_log("---- Environment ----");
	for (char** env = environ; env && *env; ++env) {
		for (const char* prefix : prefixes) {
			if (strncmp(*env, prefix, strlen(prefix)) == 0) {
				crash_log("\t%s", *env);
				break;
			}
		}
	}
}

/// What OpenMW itself says: the renderer it started and the end of its log (its fatal errors are not signals).
static void log_openmw_state()
{
	if (!g_logDir[0]) {
		return;
	}
	char path[640];
	snprintf(path, sizeof(path), "%s/renderer.status", g_logDir);
	log_file_tail("Renderer status", path, 30);
	snprintf(path, sizeof(path), "%s/openmw.log", g_logDir);
	log_file_tail("OpenMW log", path, 80, "Fatal error");
}

/// The app's own logcat, newest last (an app may read its own pid's log).
static void log_logcat(int lines)
{
	char command[160];
	snprintf(command, sizeof(command), "/system/bin/logcat -d -t %d --pid=%d 2>&1", lines, (int) getpid());
	FILE* pipe = popen(command, "r");
	if (!pipe) {
		return;
	}
	crash_log("---- logcat (this process, last %d lines) ----", lines);
	char line[512];
	while (fgets(line, sizeof(line), pipe)) {
		if (char* newline = strchr(line, '\n')) {
			*newline = 0;
		}
		// Do not quote our own crash report back to us
		if (strstr(line, "Alpha3 Crashed") || strstr(line, " Alpha3  :")) {
			continue;
		}
		crash_log("\t%s", line);
	}
	pclose(pipe);
}

void dump_register(int signal, siginfo_t* info, void* ctx)
{
	crash_log(" ");
	crash_log("========== Alpha3 Crashed ==========");

	{
		const time_t now = time(nullptr);
		struct tm local{};
		localtime_r(&now, &local);
		char stamp[40];
		strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &local);
		crash_log("Time: %s", stamp);
	}
	if (g_appInfo[0]) {
		crash_log("App: %s", g_appInfo);
	}
	logDeviceInfo();

	char threadName[32] = "?";
	prctl(PR_GET_NAME, threadName, 0, 0, 0);
	const long tid = syscall(SYS_gettid);
	crash_log("Thread: %s (tid %ld, pid %d)", threadName, tid, (int) getpid());

	auto context = reinterpret_cast<ucontext_t*>(ctx);

	crash_log("Signal: %d (%s)", signal, signal_label(signal));
	crash_log("Code: %d (%s)", info->si_code, code_label(signal, info->si_code));
	if (info->si_code > 0) {
		crash_log("Fault Address: 0x%llx", (unsigned long long) info->si_addr);
	} else {
		crash_log("Sent by pid %d uid %d", (int) info->si_pid, (int) info->si_uid);
	}

	crash_log(" ");
	crash_log("Register States:");

#ifdef __aarch64__
	for (int i = 0; i < 31; i += 2) {
		crash_log("\tx%d: 0x%llx  x%d: 0x%llx", i, (unsigned long long) context->uc_mcontext.regs[i], i + 1,
				i + 1 < 31 ? (unsigned long long) context->uc_mcontext.regs[i + 1] : 0ull);
	}
	crash_log("\tSP: 0x%llx, PC: 0x%llx, PSTATE: 0x%llx", (unsigned long long) context->uc_mcontext.sp,
			(unsigned long long) context->uc_mcontext.pc, (unsigned long long) context->uc_mcontext.pstate);

	// Where the crash really happened: name the module for PC and LR, then walk the frame pointers of the crashing thread
	// (the unwinder further down only sees the signal handler's own frames).
	auto describe = [](const char* tag, uintptr_t addr) {
		Dl_info info;
		if (dladdr(reinterpret_cast<void*>(addr), &info) && info.dli_fname) {
			crash_log("\tFault %s: %s + 0x%lx [%s]", tag, info.dli_fname, (unsigned long) (addr - (uintptr_t) info.dli_fbase),
					info.dli_sname ? info.dli_sname : "?");
		} else {
			crash_log("\tFault %s: 0x%lx (no module)", tag, (unsigned long) addr);
		}
	};
	describe("PC", context->uc_mcontext.pc);
	describe("LR", context->uc_mcontext.regs[30]);
	{
		const uintptr_t sp = context->uc_mcontext.sp;
		uintptr_t fp = context->uc_mcontext.regs[29];
		for (int depth = 0; depth < 48; ++depth) {
			if (fp == 0 || (fp & 7) != 0 || fp < sp || fp - sp > 0x800000) {
				break;
			}
			const uintptr_t* frame = reinterpret_cast<const uintptr_t*>(fp);
			const uintptr_t ret = frame[1];
			char tag[16];
			snprintf(tag, sizeof(tag), "frame #%d", depth);
			describe(tag, ret);
			if (frame[0] <= fp) {
				break;
			}
			fp = frame[0];
		}
	}
#else
	crash_log("\tr0: 0x%X, r1: 0x%X, r2: 0x%X, r3: 0x%X",
			context->uc_mcontext.arm_r0, context->uc_mcontext.arm_r1,
			context->uc_mcontext.arm_r2, context->uc_mcontext.arm_r3);
	crash_log("\tr4: 0x%X, r5: 0x%X, r6: 0x%X, r7: 0x%X",
			context->uc_mcontext.arm_r4, context->uc_mcontext.arm_r5,
			context->uc_mcontext.arm_r6, context->uc_mcontext.arm_r7);
	crash_log("\tSP: 0x%X, LR: 0x%X, PC: 0x%X",
			context->uc_mcontext.arm_sp, context->uc_mcontext.arm_lr, context->uc_mcontext.arm_pc);
#endif
}

struct BacktraceState {
	void** current;
	void** end;
};

_Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg)
{
	auto state = reinterpret_cast<BacktraceState*>(arg);
	uintptr_t pc = _Unwind_GetIP(context);  // Get the instruction pointer
	if (pc) {
		if (state->current == state->end) {
			return _URC_END_OF_STACK;  // End of stack trace
		}
		else {
			*state->current++ = reinterpret_cast<void*>(pc);
		}
	}
	return _URC_NO_REASON;
}

void dump_stack(int ignoreDepth, int maxDepth)
{
	crash_log(" ");
	crash_log("BackTrace:");

	const int max = 100;
	void* buffer[max];

	BacktraceState state{};
	state.current = buffer;
	state.end = buffer + max;

	_Unwind_Backtrace(unwind_callback, &state);

	int count = (int) (state.current - buffer);
	if (count > maxDepth) {
		count = maxDepth + 1;
	}

	for (int idx = ignoreDepth; idx < count; idx++) {
		const void* addr = buffer[idx];
		const char* symbol = "";

		Dl_info info;
		if (dladdr(addr, &info) && info.dli_sname) {
			symbol = info.dli_sname;
		}

		int status{0};
		char* demangled = __cxxabiv1::__cxa_demangle(symbol, nullptr, nullptr, &status);

		crash_log("\t#%d [%s:0x%X] + 0x%X | [%s+%d]",
				(idx - ignoreDepth),
				info.dli_fname ? info.dli_fname : "unknown file",
				(uintptr_t) info.dli_fbase,
				(uintptr_t) addr - (uintptr_t) info.dli_fbase,
				info.dli_sname ? (status == 0 ? demangled : info.dli_sname) : "unknown symbol",
				(uintptr_t) addr - (uintptr_t) info.dli_saddr);

		if (demangled) {
			free(demangled);
		}
	}
}

static void crash_handler(int signal, siginfo_t* info, void* ctx)
{
	// One thread writes the report. The others wait: when it is done the process ends, and a second report from
	// a thread that is only failing because the first one crashed would bury the cause.
	if (g_crashing.exchange(1) != 0) {
		for (;;) {
			sleep(1);
		}
	}

	// A handler that hangs (a heap lock held by the crashed thread, for instance) must not keep a dead app alive.
	::signal(SIGALRM, [](int) { _exit(113); });
	alarm(10);

	// A new file for every crash, so the report never grows into several crashes
	if (g_logDir[0]) {
		char reportPath[640];
		snprintf(reportPath, sizeof(reportPath), "%s/crash_report.txt", g_logDir);
		g_reportFd = open(reportPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
	}

	dump_register(signal, info, ctx);
	dump_stack(2);
	log_threads(syscall(SYS_gettid));
	log_modules();
	log_process_stats();
	log_environment();
	log_openmw_state();
	log_logcat(150);
	crash_log("========== End of Alpha3 crash report ==========");
	spdlog::default_logger()->flush();
	if (g_reportFd >= 0) {
		fsync(g_reportFd);
		close(g_reportFd);
		g_reportFd = -1;
	}

	// No exit() here: it runs the destructors of statics while the other threads still use them, and those threads
	// then crash in the middle of the teardown (a destroyed mutex, a null DisplaySettings) and hide the real cause.
	// The default action ends the process and leaves the system's own tombstone.
	// Hand over to the handler that was there before ours, for the system's tombstone and a proper crash record
	// (a process that just dies by signal is recorded as SIGNALED, with no tombstone). debuggerd waits for its dump
	// to finish, so the watchdog is off from here.
	alarm(0);
	if (signal > 0 && signal < NSIG) {
		const struct sigaction& previous = g_previous[signal];
		if ((previous.sa_flags & SA_SIGINFO) && previous.sa_sigaction) {
			previous.sa_sigaction(signal, info, ctx);
			return;
		}
		if (!(previous.sa_flags & SA_SIGINFO) && previous.sa_handler != SIG_DFL && previous.sa_handler != SIG_IGN
				&& previous.sa_handler) {
			previous.sa_handler(signal);
			return;
		}
	}

	// Nothing before us: straight to the kernel, since ART's signal chain sits behind signal()/sigaction() and would
	// keep handling the signal instead of letting the default action end the process.
	char defaultAction[32] = {};
	syscall(SYS_rt_sigaction, signal, defaultAction, nullptr, 8);
	if (info->si_code <= 0) {
		// Sent rather than raised by a fault: returning would carry on, so send it again
		syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), signal);
	}
}

static void terminate_handler()
{
	crash_log(" ");
	crash_log("========== Alpha3: std::terminate ==========");
	if (const std::type_info* type = abi::__cxa_current_exception_type()) {
		int status = 0;
		char* name = abi::__cxa_demangle(type->name(), nullptr, nullptr, &status);
		crash_log("Uncaught exception of type %s", status == 0 && name ? name : type->name());
		free(name);
		try {
			throw;
		} catch (const std::exception& exception) {
			crash_log("what(): %s", exception.what());
		} catch (...) {
			crash_log("(not derived from std::exception)");
		}
	} else {
		crash_log("terminate() called without an active exception");
	}
	spdlog::default_logger()->flush();
	// SIGABRT goes through crash_handler for the full report
	abort();
}

void crash_install()
{
	struct sigaction action{};
	action.sa_sigaction = crash_handler;
	sigemptyset(&action.sa_mask);
	action.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_NODEFER;
	for (int signal : { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP }) {
		sigaction(signal, &action, &g_previous[signal]);
	}
	std::set_terminate(terminate_handler);
}
