// Test-only stand-in for the Android NDK <android/log.h>.
//
// src/main/cpp/jni_bridge.cpp logs through __android_log_print(). The host test
// runner has no liblog, so this header routes those calls to stderr and keeps a
// count so a test can assert that a rejection was actually reported.
//
// NOT part of the shipped library: app/src/main/cpp/CMakeLists.txt compiles
// jni_bridge.cpp against the real NDK <android/log.h> and links -llog. Only
// src/test/cpp compiles with -I jni_stubs.

#pragma once

#include <cstdarg>
#include <cstdio>

typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT = 1,
    ANDROID_LOG_VERBOSE = 2,
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
    ANDROID_LOG_FATAL = 7,
    ANDROID_LOG_SILENT = 8
} android_LogPriority;

namespace commix_jni_stub {

// Number of __android_log_print() calls seen so far, and the text of the last
// one. Tests assert on these to prove a rejection path is actually reported
// rather than silently swallowed.
inline int& logCallCount() {
    static int count = 0;
    return count;
}

inline const char*& lastLogMessage() {
    static const char* message = nullptr;
    return message;
}

inline void recordLog(int prio, const char* tag, const char* fmt, va_list args) {
    logCallCount()++;
    static char buffer[512];
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    lastLogMessage() = buffer;
    std::fprintf(stderr, "[%s/%d] %s\n", tag, prio, buffer);
}

inline void resetLog() {
    logCallCount() = 0;
    lastLogMessage() = nullptr;
}

} // namespace commix_jni_stub

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    commix_jni_stub::recordLog(prio, tag, fmt, args);
    va_end(args);
    return 0;
}