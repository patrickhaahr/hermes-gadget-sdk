// Host stand-in for the NDK's logging header, for the JVM unit tests.
#pragma once

#include <cstdarg>
#include <cstdio>

enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6, ANDROID_LOG_FATAL = 7 };

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
  std::fprintf(stderr, "%d %s: ", prio, tag);
  va_list args;
  va_start(args, fmt);
  int n = std::vfprintf(stderr, fmt, args);
  va_end(args);
  std::fputc('\n', stderr);
  return n;
}
