// stdout and stderr of an Android app go nowhere: they are sent to logcat instead, line by line, so OpenMW's log (which
// writes every line to stdout as well as openmw.log) and OSG's notify output show up there. Replaces the logcat calls
// that were patched into OpenMW's components/debug/debugging.cpp.
#include <cstdio>
#include <string>
#include <thread>

#include <android/log.h>
#include <unistd.h>

namespace
{
    // OpenMW's lines start with "[hh:mm:ss.mmm X]", X the level
    int priorityOf(const std::string& line)
    {
        if (line.size() > 16 && line[0] == '[' && line[15] == ']')
        {
            switch (line[14])
            {
                case 'E':
                    return ANDROID_LOG_ERROR;
                case 'W':
                    return ANDROID_LOG_WARN;
                case 'V':
                    return ANDROID_LOG_VERBOSE;
                case 'D':
                    return ANDROID_LOG_DEBUG;
                default:
                    return ANDROID_LOG_INFO;
            }
        }
        return ANDROID_LOG_INFO;
    }

    void forward(int fd)
    {
        std::string line;
        char buffer[4096];
        while (true)
        {
            const ssize_t got = read(fd, buffer, sizeof buffer);
            if (got <= 0)
                return;
            for (ssize_t i = 0; i < got; ++i)
            {
                if (buffer[i] == '\n' || line.size() >= 4000)
                {
                    if (!line.empty())
                        __android_log_write(priorityOf(line), "OpenMW", line.c_str());
                    line.clear();
                    if (buffer[i] == '\n')
                        continue;
                }
                line.push_back(buffer[i]);
            }
        }
    }

    struct Redirect
    {
        Redirect()
        {
            int fds[2];
            if (pipe(fds) != 0)
                return;
            // a pipe is fully buffered by stdio, which would hold a line back until the buffer fills
            setvbuf(stdout, nullptr, _IOLBF, 0);
            setvbuf(stderr, nullptr, _IONBF, 0);
            dup2(fds[1], STDOUT_FILENO);
            dup2(fds[1], STDERR_FILENO);
            close(fds[1]);
            std::thread(forward, fds[0]).detach();
        }
    };

    const Redirect sRedirect;
}
