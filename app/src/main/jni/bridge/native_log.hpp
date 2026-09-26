#pragma once

#include <jni.h>

void InitializeNativeLog(JavaVM* vm);
int NativeLogPrint(int priority, const char* tag, const char* format, ...)
    __attribute__((format(printf, 3, 4)));
