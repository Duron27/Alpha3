#ifndef ALPHA3_TEXCONV_DEBUGLOG_H
#define ALPHA3_TEXCONV_DEBUGLOG_H

// Stands in for OpenMW's components/debug/debuglog.hpp when the support library's texture conversion is built into the
// launcher's libalpha3_texconv.so: the same Log(Debug::Level) << ... calls, written to logcat under "Alpha3Texconv"
// (to stderr on iOS, where the library is libalpha3_texconv.framework).

#include <sstream>

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif

namespace Debug
{
    enum Level
    {
        Error = 1,
        Warning = 2,
        Info = 3,
        Verbose = 4,
        Debug = 5,
    };
}

class Log
{
public:
    explicit Log(Debug::Level level)
        : mLevel(level)
    {
    }

    ~Log()
    {
#ifdef __ANDROID__
        const int priority = mLevel == Debug::Error ? ANDROID_LOG_ERROR
            : mLevel == Debug::Warning               ? ANDROID_LOG_WARN
            : mLevel == Debug::Info                  ? ANDROID_LOG_INFO
                                                     : ANDROID_LOG_DEBUG;
        __android_log_write(priority, "Alpha3Texconv", mStream.str().c_str());
#else
        std::fprintf(stderr, "Alpha3Texconv: %s\n", mStream.str().c_str());
#endif
    }

    template <class T>
    Log& operator<<(const T& value)
    {
        mStream << value;
        return *this;
    }

private:
    Debug::Level mLevel;
    std::ostringstream mStream;
};

#endif
