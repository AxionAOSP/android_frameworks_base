/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "UxPerformance"

#include <fcntl.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <string.h>

namespace android {

static jint posixFadvise(JNIEnv* env, jclass, jobject fileDescriptor, jlong offset, jlong len, jint advice) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "invalid file descriptor");
        return -1;
    }
    int ret = posix_fadvise(fd, static_cast<off_t>(offset),
            static_cast<off_t>(len), advice);
    if (ret != 0) {
        jniThrowException(env, "java/io/IOException", strerror(errno));
    }
    return ret;
}

static const JNINativeMethod sMethods[] = {
    {"nativePosixFadvise", "(Ljava/io/FileDescriptor;JJI)I", (void*) posixFadvise},
};

int register_android_server_am_UxPerformance(JNIEnv* env) {
    char const* className = "com/android/server/am/UxPerformance";
    return jniRegisterNativeMethods(env, className, sMethods, NELEM(sMethods));
}

} // namespace android
