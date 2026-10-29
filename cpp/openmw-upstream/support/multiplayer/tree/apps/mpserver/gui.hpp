#ifndef MPSERVER_GUI_H
#define MPSERVER_GUI_H

#include <atomic>

#include "servercore.hpp"

// Opens the server window, starts the server and runs until the window is closed, the server is told to stop (the quit
// command) or stopFlag is set (Ctrl+C). Returns the exit code.
// It is exported by the window module (openmw-mp-server-gui), which the program loads only when it wants a window.
using RunWindowFn = int (*)(MpServer::ServerCore& core, int argc, char** argv, std::atomic<bool>& stopFlag);
constexpr const char* kRunWindowSymbol = "mpserverRunWindow";

#endif
