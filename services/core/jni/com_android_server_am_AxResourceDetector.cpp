#define LOG_TAG "AxResourceDetector"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <unistd.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {

static const char* const kPsiPaths[] = {
    "/proc/pressure/memory",
    "/proc/pressure/cpu",
    "/proc/pressure/io"
};

static jint android_server_am_AxResourceDetector_init(JNIEnv*, jclass, jint resourceType,
                                                      jint thresholdUs, jint windowUs, jint flags) {
    if (resourceType < 0 || resourceType > 2) {
        ALOGE("Invalid resourceType: %d", resourceType);
        return -1;
    }

    const char* path = kPsiPaths[resourceType];
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("Failed to open %s: %s", path, strerror(errno));
        return -1;
    }

    char trigger[128];
    const char* stallType = (flags & 1) ? "full" : "some";
    snprintf(trigger, sizeof(trigger), "%s %d %d", stallType, thresholdUs, windowUs);

    if (write(fd, trigger, strlen(trigger) + 1) < 0) {
        ALOGE("Failed to write trigger '%s' to %s: %s", trigger, path, strerror(errno));
        close(fd);
        return -1;
    }

    int epollFd = epoll_create1(EPOLL_CLOEXEC);
    if (epollFd < 0) {
        ALOGE("Failed to create epoll fd: %s", strerror(errno));
        close(fd);
        return -1;
    }

    struct epoll_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLPRI;
    ev.data.fd = fd;

    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &ev) < 0) {
        ALOGE("Failed to add fd to epoll: %s", strerror(errno));
        close(fd);
        close(epollFd);
        return -1;
    }

    return epollFd;
}

static jint android_server_am_AxResourceDetector_waitForPressure(JNIEnv*, jclass, jint epollFd) {
    if (epollFd < 0) {
        return -1;
    }

    struct epoll_event events[1];
    int nevents = 0;
    do {
        nevents = epoll_wait(epollFd, events, 1, -1);
    } while (nevents == -1 && errno == EINTR);

    if (nevents <= 0) {
        return -1;
    }

    return 1;
}

static void android_server_am_AxResourceDetector_closeDetector(JNIEnv*, jclass, jint epollFd) {
    if (epollFd >= 0) {
        close(epollFd);
    }
}

static void android_server_am_AxResourceDetector_readahead(JNIEnv* env, jclass, jstring jpath, jboolean forceRead) {
    if (!jpath) return;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > 0) {
            posix_fadvise(fd, 0, st.st_size, POSIX_FADV_WILLNEED);
            readahead(fd, 0, st.st_size);
            if (forceRead) {
                char buf[64 * 1024];
                ssize_t n;
                size_t total = 0;
                while (total < 8 * 1024 * 1024 && (n = TEMP_FAILURE_RETRY(read(fd, buf, sizeof(buf)))) > 0) {
                    total += n;
                }
            }
        }
        close(fd);
    }
    env->ReleaseStringUTFChars(jpath, path);
}

static const JNINativeMethod sMethods[] = {
    {"init", "(IIII)I", (void*)android_server_am_AxResourceDetector_init},
    {"waitForPressure", "(I)I", (void*)android_server_am_AxResourceDetector_waitForPressure},
    {"closeDetector", "(I)V", (void*)android_server_am_AxResourceDetector_closeDetector},
    {"readahead", "(Ljava/lang/String;Z)V", (void*)android_server_am_AxResourceDetector_readahead},
};

int register_android_server_am_AxResourceDetector(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/am/AxResourceDetector", sMethods, NELEM(sMethods));
}

} // namespace android
