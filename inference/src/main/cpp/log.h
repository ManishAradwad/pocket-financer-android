#pragma once

#include <android/log.h>

#define LOG_TAG "pocketfinancer_llm"

#define LOG_INF(fmt, ...) \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, fmt, ##__VA_ARGS__)

#define LOG_ERR(fmt, ...) \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)
