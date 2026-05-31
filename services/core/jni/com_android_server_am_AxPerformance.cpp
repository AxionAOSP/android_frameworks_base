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

#define LOG_TAG "AxPerformance"

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/unique_fd.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>

#include <utils/AndroidThreads.h>
#include <utils/Log.h>

#include <fcntl.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdint>
#include <condition_variable>
#include <fstream>
#include <iterator>
#include <limits>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include <processgroup/processgroup.h>

namespace android {

using base::ParseInt;
using base::unique_fd;
using base::WriteStringToFd;

struct NodeAction {
    std::string path;
    std::string upValue;
};

struct HintEntry {
    std::vector<NodeAction> nodes;
    int64_t defaultTimeoutMs;
    std::string upCgroup;
    std::string downCgroup;
};

struct HandleEntry {
    int opcode;
    int64_t expiry;
    int cgroupPid;
    int cgroupTid;
    uint64_t sequence;
    std::vector<std::pair<std::string, std::string>> applied;
};

struct ActiveValue {
    int handle;
    std::string value;
    uint64_t sequence;
};

enum BoostOpcode {
    OP_FIRST_LAUNCH_BOOST = 1,
    OP_SUBSEQ_LAUNCH_BOOST = 2,
    OP_ACTIVITY_BOOST = 4,
    OP_ANIM_BOOST = 5,
    OP_EXIT_ANIM_BOOST = 6,
    OP_LAUNCH_ACT_SWITCH = 10,
    OP_SCROLL_BOOST = 11,
    OP_SCROLL_INPUT = 12,
    OP_SCROLL_VERTICAL = 14,
    OP_SCROLL_SCROLLER = 15,
    OP_TOUCH_BOOST = 19,
    OP_DRAG_BOOST = 21,
    OP_DRAG_START = 22,
    OP_DRAG_END = 23,
    OP_FRAME_INPUT_END = 25,
    OP_ROTATION_ANIM_BOOST = 40,
    OP_SHADE = 46,
    OP_FIRST_DRAW = 48,
    OP_BOOST_RENDERTHREAD = 63,
    OP_MISC_LAUNCHER_LOAD = 66,
    OP_GAME_LAUNCH_BOOST = 69,
};

class AxNodeLooper {
public:
    AxNodeLooper()
            : mRunning(true),
              mThread(&AxNodeLooper::threadLoop, this) {
        pthread_setname_np(mThread.native_handle(), "AxNodeLooper");
        pid_t threadTid = pthread_gettid_np(mThread.native_handle());
        androidSetThreadPriority(threadTid, PRIORITY_HIGHEST);
        SetTaskProfiles(threadTid, {"PreferIdleSet"});
    }

    AxNodeLooper(const AxNodeLooper&) = delete;
    AxNodeLooper& operator=(const AxNodeLooper&) = delete;

    ~AxNodeLooper() {
        stop();
    }

    void setValue(const std::string& path, const std::string& value) {
        if (path.empty()) return;
        std::lock_guard<std::mutex> lock(mMutex);
        uint64_t sequence = nextSequenceLocked();
        mLatestSequences[path] = sequence;
        enqueueSetLocked(&mPendingWrites, &mPendingSetIndexes, path, value, sequence);
        mCond.notify_one();
    }

    void writeValue(const std::string& path, const std::string& value) {
        if (path.empty()) return;
        std::lock_guard<std::mutex> lock(mMutex);
        mPendingWrites.push_back({path, value, false, 0});
        mCond.notify_one();
    }

    void stop() {
        bool expected = true;
        if (!mRunning.compare_exchange_strong(expected, false)) return;
        mCond.notify_all();
        if (mThread.joinable()) mThread.join();
        mFdCache.clear();
    }

private:
    struct NodeWrite {
        std::string path;
        std::string value;
        bool coalesced;
        uint64_t sequence;
    };

    using TimePoint = std::chrono::steady_clock::time_point;

    void threadLoop() {
        while (mRunning) {
            std::vector<NodeWrite> writes;
            {
                std::unique_lock<std::mutex> lock(mMutex);
                while (mRunning && mPendingWrites.empty()) {
                    if (mRetryWrites.empty()) {
                        mCond.wait(lock);
                    } else {
                        mCond.wait_until(lock, mRetryTime);
                    }
                    if (!mRunning) return;
                    if (isRetryDueLocked()) mergeRetryLocked();
                }
                if (!mRunning) return;
                if (isRetryDueLocked()) mergeRetryLocked();
                if (mPendingWrites.empty()) continue;
                writes.swap(mPendingWrites);
                mPendingSetIndexes.clear();
            }
            processWrites(writes);
        }
    }

    bool isRetryDueLocked() const {
        return !mRetryWrites.empty() && std::chrono::steady_clock::now() >= mRetryTime;
    }

    void mergeRetryLocked() {
        for (const NodeWrite& write : mRetryWrites) {
            auto it = mLatestSequences.find(write.path);
            if (it != mLatestSequences.end() && it->second == write.sequence) {
                enqueueSetLocked(&mPendingWrites, &mPendingSetIndexes,
                        write.path, write.value, write.sequence);
            }
        }
        mRetryWrites.clear();
        mRetrySetIndexes.clear();
        mRetryTime = TimePoint::max();
    }

    void processWrites(const std::vector<NodeWrite>& writes) {
        std::vector<NodeWrite> setWrites;
        setWrites.reserve(writes.size());
        for (const NodeWrite& write : writes) {
            if (write.coalesced) {
                setWrites.push_back(write);
            } else {
                writeNode(write.path, write.value);
            }
        }
        std::vector<NodeWrite> remaining = std::move(setWrites);
        for (int pass = 0; pass < 2 && !remaining.empty(); pass++) {
            std::vector<NodeWrite> failed;
            failed.reserve(remaining.size());
            for (const NodeWrite& write : remaining) {
                auto current = mWrittenValues.find(write.path);
                if (current != mWrittenValues.end() && current->second == write.value) continue;
                if (writeNode(write.path, write.value)) {
                    mWrittenValues[write.path] = write.value;
                } else {
                    failed.push_back(write);
                }
            }
            remaining.swap(failed);
        }
        scheduleRetry(remaining);
    }

    void scheduleRetry(const std::vector<NodeWrite>& writes) {
        if (writes.empty()) return;
        std::lock_guard<std::mutex> lock(mMutex);
        bool added = false;
        for (const NodeWrite& write : writes) {
            auto it = mLatestSequences.find(write.path);
            if (it != mLatestSequences.end() && it->second == write.sequence
                    && mPendingSetIndexes.find(write.path) == mPendingSetIndexes.end()) {
                enqueueSetLocked(&mRetryWrites, &mRetrySetIndexes,
                        write.path, write.value, write.sequence);
                added = true;
            }
        }
        if (added) {
            TimePoint retryTime =
                    std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
            if (mRetryTime == TimePoint::max() || retryTime < mRetryTime) mRetryTime = retryTime;
            mCond.notify_one();
        }
    }

    void enqueueSetLocked(std::vector<NodeWrite>* writes,
            std::unordered_map<std::string, size_t>* indexes,
            const std::string& path, const std::string& value, uint64_t sequence) {
        auto it = indexes->find(path);
        if (it != indexes->end()) {
            NodeWrite& write = (*writes)[it->second];
            write.value = value;
            write.sequence = sequence;
            return;
        }
        (*indexes)[path] = writes->size();
        writes->push_back({path, value, true, sequence});
    }

    uint64_t nextSequenceLocked() {
        if (mNextSequence == 0) mNextSequence = 1;
        return mNextSequence++;
    }

    bool writeNode(const std::string& path, const std::string& value) {
        auto it = mFdCache.find(path);
        if (it == mFdCache.end()) {
            unique_fd fd(TEMP_FAILURE_RETRY(open(path.c_str(), O_WRONLY | O_CLOEXEC)));
            if (fd < 0) return false;
            it = mFdCache.emplace(path, std::move(fd)).first;
        }
        if (WriteStringToFd(value, it->second)) return true;
        if (errno != EBADF && errno != EIO) return false;
        mFdCache.erase(it);
        unique_fd fd(TEMP_FAILURE_RETRY(open(path.c_str(), O_WRONLY | O_CLOEXEC)));
        if (fd < 0) return false;
        const bool written = WriteStringToFd(value, fd);
        mFdCache.emplace(path, std::move(fd));
        return written;
    }

    std::mutex mMutex;
    std::condition_variable mCond;
    std::atomic<bool> mRunning;
    uint64_t mNextSequence = 1;
    std::vector<NodeWrite> mPendingWrites;
    std::unordered_map<std::string, size_t> mPendingSetIndexes;
    std::vector<NodeWrite> mRetryWrites;
    std::unordered_map<std::string, size_t> mRetrySetIndexes;
    std::unordered_map<std::string, uint64_t> mLatestSequences;
    std::unordered_map<std::string, std::string> mWrittenValues;
    std::unordered_map<std::string, unique_fd> mFdCache;
    TimePoint mRetryTime = TimePoint::max();
    std::thread mThread;
};

class AxHintManager {
public:
    static AxHintManager* getInstance() {
        static AxHintManager instance;
        return &instance;
    }

    AxHintManager(const AxHintManager&) = delete;
    AxHintManager& operator=(const AxHintManager&) = delete;

    void setBoostData(const std::string& path, const std::string& value) {
        std::lock_guard<std::mutex> lock(mMutex);
        std::string resolvedPath = normalizeResourcePath(path);
        mRestoreValues[resolvedPath] = value;
        if (mResourceRefs.find(resolvedPath) != mResourceRefs.end()) return;
        writeClamped(resolvedPath, value);
    }

    bool isCompositionBoosting() const {
        std::lock_guard<std::mutex> lock(mMutex);
        for (const auto& [_, entry] : mHandles) {
            if (isCompositionOpcode(entry.opcode)) return true;
        }
        return false;
    }

    bool shouldDeferPss() const {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mHintCount == 0) return false;
        int64_t now = nowMs();
        for (int i = 0; i < mHintCount; i++) {
            if (now - mLastHintMs[i] < kProcessPssDeferMs) return true;
        }
        return false;
    }

    struct PathBound {
        int value;
        bool isFloor;
    };

    void setCpuFreqBound(const std::string& path, int boundValue, bool isFloor) {
        std::lock_guard<std::mutex> lock(mMutex);
        mBounds[path] = {boundValue, isFloor};
        writeClamped(path, std::to_string(boundValue));
    }

    void removeCpuFreqBounds() {
        std::lock_guard<std::mutex> lock(mMutex);
        std::vector<std::pair<std::string, std::string>> toRestore;
        for (const auto& kv : mBounds) {
            auto rit = mRestoreValues.find(kv.first);
            if (rit != mRestoreValues.end())
                toRestore.push_back({kv.first, rit->second});
        }
        mBounds.clear();
        for (const auto& kv : toRestore)
            writeClamped(kv.first, kv.second);
    }

    void setThermalCeiling(const std::string& path, int ceiling) {
        std::lock_guard<std::mutex> lock(mMutex);
        mThermalCeilings[path] = ceiling;
        writeClamped(path, std::to_string(ceiling));
    }

    void removeThermalCeiling(const std::string& path) {
        std::lock_guard<std::mutex> lock(mMutex);
        mThermalCeilings.erase(path);
        if (mResourceRefs.find(path) != mResourceRefs.end()) {
            restoreEffectiveValueLocked(path);
            return;
        }
        auto bit = mRestoreValues.find(path);
        if (bit != mRestoreValues.end()) {
            writeClamped(path, bit->second);
            return;
        }
        auto oit = mOriginalValues.find(path);
        if (oit != mOriginalValues.end()) writeClamped(path, oit->second);
    }

    void updateTopApp(int pid, int rTid) {
        std::lock_guard<std::mutex> lock(mMutex);
        mTaskProfiles.erase(mRtPid);
        mTaskProfiles.erase(mRtTid);
        mTaskProfiles.erase(pid);
        mTaskProfiles.erase(rTid);
        mRtPid = pid;
        mRtTid = rTid;
    }

    int createHandle(int opcode, int64_t durMs) {
        std::lock_guard<std::mutex> lock(mMutex);
        return createHandleLocked(opcode, durMs);
    }

    void releaseHandle(int handle) {
        std::lock_guard<std::mutex> lock(mMutex);
        releaseHandleLocked(handle);
    }

    void setUiBoostActive(bool active) {
        std::lock_guard<std::mutex> lock(mMutex);
        mUiBoostActive = active;
    }

private:
    void releaseOpcodeLocked(int opcode) {
        if (opcode >= 0 && opcode < mHintCount) {
            int handle = mOpcodeHandles[opcode];
            auto hit = mHandles.find(handle);
            if (handle > 0 && hit != mHandles.end() && hit->second.opcode == opcode) {
                releaseHandleLocked(handle);
                return;
            }
            mOpcodeHandles[opcode] = 0;
        }
        for (auto it = mHandles.begin(); it != mHandles.end(); ) {
            if (it->second.opcode == opcode) {
                int h = it->first;
                ++it;
                releaseHandleLocked(h);
            } else {
                ++it;
            }
        }
    }

    int createHandleLocked(int opcode, int64_t durMs) {
        if (opcode <= 0 || opcode >= mHintCount) return -1;
        if (durMs == 0) {
            releaseOpcodeLocked(opcode);
            return -1;
        }
        HintEntry& entry = mHints[opcode];
        if (entry.nodes.empty() && entry.upCgroup.empty()) return -1;
        int64_t now = nowMs();
        int64_t timeoutMs = (durMs == -2) ? entry.defaultTimeoutMs : (durMs > 0 ? durMs : 0);
        int64_t expiry = (timeoutMs > 0) ? now + timeoutMs : 0;
        if (mOpcodeHandles[opcode] > 0) {
            int handle = mOpcodeHandles[opcode];
            auto hit = mHandles.find(handle);
            if (hit != mHandles.end()) {
                uint64_t sequence = nextSequenceLocked();
                if (expiry == 0 || (hit->second.expiry > 0 && expiry > hit->second.expiry)) {
                    hit->second.expiry = expiry;
                }
                hit->second.sequence = sequence;
                std::vector<std::string> paths;
                paths.reserve(hit->second.applied.size());
                for (const auto& [path, _] : hit->second.applied) {
                    refreshActiveValueLocked(path, handle, sequence);
                    addUniquePath(&paths, path);
                }
                restoreEffectiveValuesLocked(paths);
                mLastHintMs[opcode] = now;
                if (shouldNotifyTimerLocked(hit->second.expiry, handle)) mCond.notify_one();
                return handle;
            } else {
                mOpcodeHandles[opcode] = 0;
            }
        }
        int handle = nextHandleLocked();
        if (handle < 0) return -1;
        uint64_t sequence = nextSequenceLocked();
        std::vector<std::pair<std::string, std::string>> applied;
        std::vector<std::string> paths;
        paths.reserve(entry.nodes.size());
        for (auto& node : entry.nodes) {
            auto rit = mResourceRefs.find(node.path);
            if (rit == mResourceRefs.end()
                    && mRestoreValues.find(node.path) == mRestoreValues.end()) {
                std::string orig = readSysfs(node.path);
                if (!orig.empty()) mOriginalValues[node.path] = orig;
            }
            mResourceRefs[node.path]++;
            mActiveValues[node.path].push_back({handle, node.upValue, sequence});
            addUniquePath(&paths, node.path);
            applied.push_back({node.path, node.upValue});
        }
        restoreEffectiveValuesLocked(paths);
        int cgroupTid = 0;
        int cgroupPid = 0;
        if (!entry.upCgroup.empty() && !isUiBoostActive()) {
            cgroupTid = mRtTid;
            cgroupPid = mRtPid;
            acquireTaskProfileLocked(cgroupPid, entry.upCgroup);
            acquireTaskProfileLocked(cgroupTid, entry.upCgroup);
        }
        mLastHintMs[opcode] = now;
        mHandles[handle] = {
                opcode, expiry, cgroupPid, cgroupTid, sequence, std::move(applied)};
        mOpcodeHandles[opcode] = handle;
        if (shouldNotifyTimerLocked(expiry, handle)) mCond.notify_one();
        return handle;
    }

    void releaseHandleLocked(int handle) {
        auto hit = mHandles.find(handle);
        if (hit == mHandles.end()) return;
        if (hit->second.opcode >= 0 && hit->second.opcode < mHintCount
                && (hit->second.cgroupPid > 0 || hit->second.cgroupTid > 0)) {
            HintEntry& entry = mHints[hit->second.opcode];
            if (!entry.upCgroup.empty()) {
                releaseTaskProfileLocked(hit->second.cgroupPid, entry.upCgroup,
                        entry.downCgroup, handle);
                releaseTaskProfileLocked(hit->second.cgroupTid, entry.upCgroup,
                        entry.downCgroup, handle);
            }
        }
        std::vector<std::string> activeRestorePaths;
        std::vector<std::pair<std::string, std::string>> baseRestores;
        activeRestorePaths.reserve(hit->second.applied.size());
        baseRestores.reserve(hit->second.applied.size());
        for (auto& [path, val] : hit->second.applied) {
            auto rit = mResourceRefs.find(path);
            if (rit != mResourceRefs.end()) {
                removeActiveValueLocked(path, handle);
                rit->second--;
                if (rit->second <= 0) {
                    mResourceRefs.erase(rit);
                    mActiveValues.erase(path);
                    auto bit = mRestoreValues.find(path);
                    if (bit != mRestoreValues.end()) {
                        baseRestores.push_back({path, bit->second});
                    } else {
                        auto oit = mOriginalValues.find(path);
                        if (oit != mOriginalValues.end()) {
                            baseRestores.push_back({path, oit->second});
                        }
                    }
                    mOriginalValues.erase(path);
                } else {
                    addUniquePath(&activeRestorePaths, path);
                }
            }
        }
        writeBaseValuesLocked(baseRestores);
        restoreEffectiveValuesLocked(activeRestorePaths);
        if (hit->second.opcode >= 0 && hit->second.opcode < mHintCount) {
            if (mOpcodeHandles[hit->second.opcode] == handle) {
                mOpcodeHandles[hit->second.opcode] = 0;
            }
        }
        mHandles.erase(hit);
    }

    bool isUiBoostActive() const { return mUiBoostActive; }

    void writeClamped(const std::string& path, const std::string& value) {
        auto ceilingIt = mThermalCeilings.find(path);
        auto boundIt = mBounds.find(path);
        auto cur = mCurrentValues.find(path);
        if (ceilingIt == mThermalCeilings.end() && boundIt == mBounds.end()) {
            if (cur != mCurrentValues.end() && cur->second == value) return;
            mCurrentValues[path] = value;
            mNodeLooper.setValue(path, value);
            return;
        }
        std::string clamped = value;
        int val = 0;
        bool validInt = ParseInt(value, &val);
        if (validInt) {
            if (ceilingIt != mThermalCeilings.end() && val > ceilingIt->second) {
                val = ceilingIt->second;
                clamped = std::to_string(val);
            }
            if (boundIt != mBounds.end()) {
                if (boundIt->second.isFloor && val < boundIt->second.value) {
                    val = boundIt->second.value;
                    clamped = std::to_string(val);
                } else if (!boundIt->second.isFloor && val > boundIt->second.value) {
                    val = boundIt->second.value;
                    clamped = std::to_string(val);
                }
            }
        }
        if (cur != mCurrentValues.end() && cur->second == clamped) return;
        mCurrentValues[path] = clamped;
        mNodeLooper.setValue(path, clamped);
    }

    AxHintManager()
            : mUseStune(detectStuneBackend()),
              mRunning(true),
              mTimerThread(&AxHintManager::timerLoop, this) {
        std::unordered_map<int, ResourceEntry> resMap;
        loadResources("/vendor/etc/ax_perf_resources.xml", resMap);
        if (resMap.empty()) loadResources("/system/etc/ax_perf_resources.xml", resMap);
        loadBoosts("/vendor/etc/ax_perf_boosts.xml", resMap);
        if (mHints.empty()) loadBoosts("/system/etc/ax_perf_boosts.xml", resMap);
        mHintCount = mHints.size();
        mLastHintMs.resize(mHintCount, 0);
        mOpcodeHandles.resize(mHintCount, 0);
        pthread_setname_np(mTimerThread.native_handle(), "AxTimerLoop");
        pid_t timerTid = pthread_gettid_np(mTimerThread.native_handle());
        SetTaskProfiles(timerTid, {"ProcessCapacityLow"});
    }

    ~AxHintManager() {
        mRunning = false;
        mCond.notify_all();
        if (mTimerThread.joinable()) mTimerThread.join();
        mNodeLooper.stop();
    }

    void timerLoop() {
        while (mRunning) {
            std::unique_lock<std::mutex> lock(mMutex);
            int64_t nextExpiry = 0;
            for (const auto& [_, entry] : mHandles) {
                if (entry.expiry <= 0) continue;
                nextExpiry = nextExpiry == 0 ? entry.expiry : std::min(nextExpiry, entry.expiry);
            }
            if (nextExpiry == 0) {
                mCond.wait(lock);
                if (!mRunning) return;
                continue;
            }
            int64_t now = nowMs();
            if (now < nextExpiry) {
                mCond.wait_for(lock, std::chrono::milliseconds(nextExpiry - now));
                if (!mRunning) return;
                continue;
            }
            if (!mRunning) return;
            for (auto it = mHandles.begin(); it != mHandles.end(); ) {
                if (it->second.expiry > 0 && now >= it->second.expiry) {
                    int h = it->first;
                    ++it;
                    releaseHandleLocked(h);
                } else {
                    ++it;
                }
            }
        }
    }

    static int64_t nowMs() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
    }

    static std::string readSysfs(const std::string& path) {
        unique_fd fd(TEMP_FAILURE_RETRY(open(path.c_str(), O_RDONLY | O_CLOEXEC)));
        if (fd < 0) return "";
        char buf[256] = {};
        ssize_t n = TEMP_FAILURE_RETRY(read(fd.get(), buf, sizeof(buf) - 1));
        if (n > 0) {
            while (n > 0 && (buf[n-1] == '\n' || buf[n-1] == ' ')) n--;
            buf[n] = '\0';
        }
        return buf;
    }

    struct ResourceEntry {
        std::string path;
        std::string defaultVal;
    };

    static std::string attr(const std::string& text, const std::string& name) {
        size_t s = text.find(name + "=\"");
        if (s == std::string::npos) return "";
        s += name.size() + 2;
        size_t e = text.find('"', s);
        return (e == std::string::npos) ? "" : text.substr(s, e - s);
    }

    static std::vector<std::pair<int, std::string>> parseSetActions(const std::string& body) {
        std::vector<std::pair<int, std::string>> actions;
        size_t pos = 0;
        while ((pos = body.find("<set ", pos)) != std::string::npos) {
            size_t end = body.find("/>", pos);
            if (end == std::string::npos) break;
            std::string chunk = body.substr(pos, end - pos);
            pos = end + 2;
            std::string idStr = attr(chunk, "id");
            if (idStr.empty()) continue;
            int id = 0;
            if (!ParseInt(idStr, &id)) continue;
            actions.push_back({id, attr(chunk, "value")});
        }
        return actions;
    }

    void loadResources(const std::string& path, std::unordered_map<int, ResourceEntry>& resMap) {
        std::ifstream f(path);
        if (!f) return;
        std::string xml((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        size_t pos = 0;
        while ((pos = xml.find("<resource ", pos)) != std::string::npos) {
            size_t end = xml.find("/>", pos);
            if (end == std::string::npos) break;
            std::string chunk = xml.substr(pos, end - pos);
            pos = end + 2;
            std::string idStr = attr(chunk, "id");
            std::string rpath = normalizeResourcePath(attr(chunk, "path"));
            std::string def = attr(chunk, "default");
            if (idStr.empty() || rpath.empty()) continue;
            int id = 0;
            if (!ParseInt(idStr, &id)) continue;
            resMap[id] = {rpath, def.empty() ? "0" : def};
        }
    }

    void loadBoosts(const std::string& path, std::unordered_map<int, ResourceEntry>& resMap) {
        std::ifstream f(path);
        if (!f) return;
        std::string xml((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        size_t pos = 0;
        int maxOpcode = 0;
        std::unordered_map<int, HintEntry> hintsNew;

        std::unordered_map<int, std::vector<std::pair<int, std::string>>> brMap;
        while ((pos = xml.find("<boost-resource ", pos)) != std::string::npos) {
            size_t close = xml.find('>', pos);
            if (close == std::string::npos) break;
            std::string header = xml.substr(pos, close - pos);
            bool selfClosing = close > pos && xml[close - 1] == '/';
            pos = close + 1;
            std::string body;
            if (!selfClosing) {
                size_t end = xml.find("</boost-resource>", pos);
                if (end == std::string::npos) break;
                body = xml.substr(pos, end - pos);
                pos = end + 17;
            }
            std::string idStr = attr(header, "id");
            if (idStr.empty()) continue;
            int brId = 0;
            if (!ParseInt(idStr, &brId)) continue;
            brMap[brId] = parseSetActions(body);
        }

        pos = 0;

        while ((pos = xml.find("<boost ", pos)) != std::string::npos) {
            size_t close = xml.find('>', pos);
            if (close == std::string::npos) break;
            std::string header = xml.substr(pos, close - pos);
            bool selfClosing = close > pos && xml[close - 1] == '/';
            pos = close + 1;
            std::string body;
            if (!selfClosing) {
                size_t ct = xml.find("</boost>", pos);
                if (ct == std::string::npos) break;
                body = xml.substr(pos, ct - pos);
                pos = ct + 8;
            }

            int opcode = 0;
            int64_t timeout = 0;
            if (!ParseInt(attr(header, "opcode"), &opcode)
                    || !ParseInt(attr(header, "timeout_ms"), &timeout)) {
                continue;
            }
            std::string upCgroup = attr(header, "uc");
            std::string downCgroup = attr(header, "dc");
            std::vector<NodeAction> nodes;

            for (auto& [rid, parsedValue] : parseSetActions(body)) {
                auto rit = resMap.find(rid);
                if (rit != resMap.end()) {
                    std::string val = parsedValue.empty() ? rit->second.defaultVal : parsedValue;
                    nodes.push_back({rit->second.path, val});
                } else if (parsedValue.empty()) {
                    auto brIt = brMap.find(rid);
                    if (brIt != brMap.end()) {
                        for (auto& [subId, subVal] : brIt->second) {
                            auto subRit = resMap.find(subId);
                            if (subRit != resMap.end()) {
                                std::string sv = subVal.empty() ? subRit->second.defaultVal : subVal;
                                nodes.push_back({subRit->second.path, sv});
                            }
                        }
                    }
                }
            }

            if (opcode > 0 && (!nodes.empty() || !upCgroup.empty())) {
                hintsNew[opcode] = {nodes, timeout, upCgroup, downCgroup};
                if (opcode > maxOpcode) maxOpcode = opcode;
            }
        }

        if (!hintsNew.empty()) {
            mHints.resize(maxOpcode + 1);
            mLastHintMs.resize(maxOpcode + 1, 0);
            for (auto& kv : hintsNew) {
                mHints[kv.first] = kv.second;
            }
            mHintCount = maxOpcode + 1;
            mOpcodeHandles.resize(mHintCount, 0);
        }
    }

    std::vector<HintEntry> mHints;
    std::vector<int64_t> mLastHintMs;
    std::vector<int> mOpcodeHandles;
    std::unordered_map<std::string, std::string> mRestoreValues;
    std::unordered_map<std::string, int> mThermalCeilings;
    std::unordered_map<std::string, PathBound> mBounds;
    int mHintCount = 0;
    bool mUiBoostActive = false;
    int mNextHandle = 1;
    uint64_t mNextSequence = 1;
    std::unordered_map<int, HandleEntry> mHandles;
    std::unordered_map<int, std::string> mTaskProfiles;
    std::unordered_map<std::string, int> mTaskProfileRefs;
    std::unordered_map<std::string, int> mResourceRefs;
    std::unordered_map<std::string, std::vector<ActiveValue>> mActiveValues;
    std::unordered_map<std::string, std::string> mOriginalValues;
    std::unordered_map<std::string, std::string> mCurrentValues;
    const bool mUseStune;
    AxNodeLooper mNodeLooper;
    int mRtPid = 0;
    int mRtTid = 0;
    mutable std::mutex mMutex;
    std::condition_variable mCond;
    std::atomic<bool> mRunning;
    std::thread mTimerThread;
    static constexpr int64_t kProcessPssDeferMs = 2500;

    int nextHandleLocked() {
        if (mHandles.size() >= static_cast<size_t>(std::numeric_limits<int>::max() - 1)) {
            return -1;
        }
        for (int tries = 0; tries < std::numeric_limits<int>::max() - 1; tries++) {
            if (mNextHandle <= 0) mNextHandle = 1;
            int handle = mNextHandle++;
            if (mHandles.find(handle) == mHandles.end()) return handle;
        }
        return -1;
    }

    uint64_t nextSequenceLocked() {
        if (mNextSequence == 0) mNextSequence = 1;
        return mNextSequence++;
    }

    bool shouldNotifyTimerLocked(int64_t expiry, int ignoreHandle) const {
        if (expiry <= 0) return false;
        for (const auto& [handle, entry] : mHandles) {
            if (handle != ignoreHandle && entry.expiry > 0 && entry.expiry <= expiry) {
                return false;
            }
        }
        return true;
    }

    void applyTaskProfileLocked(int taskId, const std::string& profile) {
        if (taskId <= 0 || profile.empty()) return;
        auto it = mTaskProfiles.find(taskId);
        if (it != mTaskProfiles.end() && it->second == profile) return;
        const std::string_view profileView(profile);
        SetTaskProfiles(taskId, {profileView}, true);
        applyStuneProfileLocked(taskId, profile);
        mTaskProfiles[taskId] = profile;
    }

    void applyStuneProfileLocked(int taskId, const std::string& profile) {
        if (!mUseStune || taskId <= 0) return;
        std::string group = stuneGroupForProfile(profile);
        if (group.empty()) return;
        std::string taskPath = "/dev/stune/" + group + "/tasks";
        if (!pathExists(taskPath)) return;
        mNodeLooper.writeValue(taskPath, std::to_string(taskId));
        if (profile == "SvpPolicy") {
            writeStuneValue(group, "schedtune.boost", "100");
            writeStuneValue(group, "schedtune.prefer_idle", "1");
        }
    }

    void writeStuneValue(
            const std::string& group, const std::string& file, const std::string& value) {
        std::string path = "/dev/stune/" + group + "/" + file;
        if (pathExists(path)) writeClamped(path, value);
    }

    void acquireTaskProfileLocked(int taskId, const std::string& profile) {
        if (taskId <= 0 || profile.empty()) return;
        mTaskProfileRefs[taskProfileRefKey(taskId, profile)]++;
        applyTaskProfileLocked(taskId, profile);
    }

    void releaseTaskProfileLocked(int taskId, const std::string& profile,
            const std::string& fallbackProfile, int releasedHandle) {
        if (taskId <= 0 || profile.empty()) return;
        std::string refKey = taskProfileRefKey(taskId, profile);
        auto refIt = mTaskProfileRefs.find(refKey);
        if (refIt != mTaskProfileRefs.end()) {
            refIt->second--;
            if (refIt->second > 0) return;
            mTaskProfileRefs.erase(refIt);
        }
        std::string latestProfile = latestTaskProfileLocked(taskId, releasedHandle);
        applyTaskProfileLocked(taskId, latestProfile.empty() ? fallbackProfile : latestProfile);
    }

    std::string latestTaskProfileLocked(int taskId, int releasedHandle) const {
        uint64_t latestSequence = 0;
        std::string latestProfile;
        for (const auto& [handle, entry] : mHandles) {
            if (handle == releasedHandle || entry.opcode < 0 || entry.opcode >= mHintCount) {
                continue;
            }
            if (entry.cgroupPid != taskId && entry.cgroupTid != taskId) continue;
            const std::string& profile = mHints[entry.opcode].upCgroup;
            if (!profile.empty() && entry.sequence >= latestSequence) {
                latestSequence = entry.sequence;
                latestProfile = profile;
            }
        }
        return latestProfile;
    }

    static std::string taskProfileRefKey(int taskId, const std::string& profile) {
        return std::to_string(taskId) + '\n' + profile;
    }

    static bool pathExists(const std::string& path) {
        return TEMP_FAILURE_RETRY(access(path.c_str(), F_OK)) == 0;
    }

    static bool detectStuneBackend() {
        return pathExists("/dev/stune/top-app/tasks") && !hasUclampBackend();
    }

    static bool hasUclampBackend() {
        return pathExists("/dev/cpuctl/top-app/cpu.uclamp.min")
                || pathExists("/dev/cpuctl/foreground/cpu.uclamp.min")
                || pathExists("/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive");
    }

    std::string normalizeResourcePath(const std::string& path) const {
        if (!mUseStune) return path;
        return stunePathForUclamp(path);
    }

    static std::string stunePathForUclamp(const std::string& path) {
        static const std::string prefix = "/dev/cpuctl/";
        if (path.rfind(prefix, 0) != 0) return path;
        size_t groupStart = prefix.size();
        size_t groupEnd = path.find('/', groupStart);
        if (groupEnd == std::string::npos) return path;
        std::string file = path.substr(groupEnd + 1);
        if (file != "cpu.uclamp.min" && file != "cpu.uclamp.latency_sensitive") return path;
        std::string group = path.substr(groupStart, groupEnd - groupStart);
        std::string stuneFile = file == "cpu.uclamp.min"
                ? "schedtune.boost" : "schedtune.prefer_idle";
        return "/dev/stune/" + group + "/" + stuneFile;
    }

    static std::string stuneGroupForProfile(const std::string& profile) {
        if (profile == "HighEnergySaving" || profile == "ProcessCapacityLow"
                || profile == "CPUSET_SP_BACKGROUND" || profile == "SCHED_SP_BACKGROUND") {
            return "background";
        }
        if (profile == "HighPerformance" || profile == "ProcessCapacityHigh"
                || profile == "CPUSET_SP_FOREGROUND" || profile == "SCHED_SP_FOREGROUND") {
            return "foreground";
        }
        if (profile == "HighPerformanceWI" || profile == "ProcessCapacityHighWI"
                || profile == "CPUSET_SP_FOREGROUND_WINDOW"
                || profile == "SCHED_SP_FOREGROUND_WINDOW") {
            return "foreground_window";
        }
        if (profile == "MaxPerformance" || profile == "ProcessCapacityMax"
                || profile == "CPUSET_SP_TOP_APP" || profile == "SCHED_SP_TOP_APP"
                || profile == "InputPolicy" || profile == "SFMainPolicy"
                || profile == "SFRenderEnginePolicy") {
            return "top-app";
        }
        if (profile == "RealtimePerformance" || profile == "SCHED_SP_RT_APP") {
            return "rt";
        }
        if (profile == "ServicePerformance" || profile == "ServiceCapacityLow"
                || profile == "CPUSET_SP_SYSTEM" || profile == "SCHED_SP_SYSTEM"
                || profile == "OtaProfiles") {
            return "system-background";
        }
        if (profile == "NormalPerformance") {
            return "system";
        }
        if (profile == "ServiceCapacityRestricted" || profile == "CPUSET_SP_RESTRICTED") {
            return "restricted";
        }
        if (profile == "CameraServicePerformance" || profile == "CameraServiceCapacity") {
            return "camera-daemon";
        }
        if (profile == "NNApiHALPerformance") {
            return "nnapi-hal";
        }
        if (profile == "Dex2oatPerformance" || profile == "Dex2OatBootComplete"
                || profile == "Dex2OatBackground") {
            return "dex2oat";
        }
        if (profile == "SvpPolicy" || profile == "SvpSched" || profile == "CPUSET_SP_SVP"
                || profile == "SCHED_SP_SVP") {
            return "svp";
        }
        if (profile == "HighAxPerformance" || profile == "ProcessAxCapacityHigh"
                || profile == "CPUSET_SP_AX_FOREGROUND" || profile == "SCHED_SP_AX_FG") {
            return "ax_foreground";
        }
        if (profile == "AudioPolicy" || profile == "CPUSET_SP_AUDIO") {
            return "audio-app";
        }
        if (profile == "CpuctlLBackground" || profile == "CpuSetLBackground"
                || profile == "CPUSET_SP_LBACKGROUND" || profile == "SCHED_SP_LBACKGROUND") {
            return "l-background";
        }
        if (profile == "CpuctlHBackground" || profile == "CpuSetHBackground"
                || profile == "CPUSET_SP_HBACKGROUND" || profile == "SCHED_SP_HBACKGROUND") {
            return "h-background";
        }
        if (profile == "CpuctlSystemUI" || profile == "CpuSetSystemUI"
                || profile == "SchedtuneSystemUI" || profile == "CPUSET_SP_SYSTEMUI"
                || profile == "SCHED_SP_SYSTEMUI") {
            return "systemui";
        }
        return "";
    }

    static bool isCompositionOpcode(int opcode) {
        return opcode == OP_FIRST_LAUNCH_BOOST || opcode == OP_SUBSEQ_LAUNCH_BOOST
                || opcode == OP_ACTIVITY_BOOST || opcode == OP_ANIM_BOOST
                || opcode == OP_EXIT_ANIM_BOOST || opcode == OP_LAUNCH_ACT_SWITCH
                || opcode == OP_SCROLL_BOOST || opcode == OP_SCROLL_INPUT
                || opcode == OP_SCROLL_VERTICAL || opcode == OP_SCROLL_SCROLLER
                || opcode == OP_TOUCH_BOOST || opcode == OP_DRAG_BOOST
                || opcode == OP_DRAG_START || opcode == OP_DRAG_END
                || (opcode >= OP_FRAME_INPUT_END && opcode <= OP_ROTATION_ANIM_BOOST)
                || opcode == OP_SHADE || opcode == OP_FIRST_DRAW
                || opcode == OP_BOOST_RENDERTHREAD || opcode == OP_MISC_LAUNCHER_LOAD
                || opcode == OP_GAME_LAUNCH_BOOST;
    }

    void removeActiveValueLocked(const std::string& path, int handle) {
        auto vit = mActiveValues.find(path);
        if (vit == mActiveValues.end()) return;
        auto& values = vit->second;
        for (auto it = values.end(); it != values.begin(); ) {
            --it;
            if (it->handle == handle) {
                values.erase(it);
                if (values.empty()) mActiveValues.erase(vit);
                return;
            }
        }
    }

    void refreshActiveValueLocked(const std::string& path, int handle, uint64_t sequence) {
        auto vit = mActiveValues.find(path);
        if (vit == mActiveValues.end()) return;
        for (ActiveValue& value : vit->second) {
            if (value.handle == handle) value.sequence = sequence;
        }
    }

    static void addUniquePath(std::vector<std::string>* paths, const std::string& path) {
        if (std::find(paths->begin(), paths->end(), path) == paths->end()) {
            paths->push_back(path);
        }
    }

    void restoreEffectiveValuesLocked(const std::vector<std::string>& paths) {
        for (const std::string& path : paths) {
            restoreEffectiveValueLocked(path);
        }
    }

    void writeBaseValuesLocked(const std::vector<std::pair<std::string, std::string>>& values) {
        for (const auto& [path, value] : values) {
            writeClamped(path, value);
        }
    }

    void restoreEffectiveValueLocked(const std::string& path) {
        const ActiveValue* value = effectiveValueLocked(path);
        if (value == nullptr) return;
        writeClamped(path, value->value);
    }

    const ActiveValue* effectiveValueLocked(const std::string& path) const {
        auto vit = mActiveValues.find(path);
        if (vit == mActiveValues.end() || vit->second.empty()) return nullptr;
        const ActiveValue* best = &vit->second.front();
        for (const ActiveValue& value : vit->second) {
            if (isStrongerValue(path, value, *best)) best = &value;
        }
        return best;
    }

    static bool isStrongerValue(
            const std::string& path, const ActiveValue& candidate, const ActiveValue& current) {
        int candidateValue = 0;
        int currentValue = 0;
        if (ParseInt(candidate.value, &candidateValue) && ParseInt(current.value, &currentValue)
                && candidateValue != currentValue) {
            return lowerValueIsStronger(path)
                    ? candidateValue < currentValue : candidateValue > currentValue;
        }
        return candidate.sequence >= current.sequence;
    }

    static bool lowerValueIsStronger(const std::string& path) {
        return path.find("cpu_dma_latency") != std::string::npos
                || path.find("pm_qos") != std::string::npos
                || path.find("pmqos") != std::string::npos;
    }
};

static jlong native_perf_hint(JNIEnv*, jclass, jint opcode, jlong durMs) {
    return static_cast<jlong>(AxHintManager::getInstance()->createHandle(opcode, durMs));
}

static void native_perf_hint_rel(JNIEnv*, jclass, jlong handle) {
    AxHintManager::getInstance()->releaseHandle(static_cast<int>(handle));
}

static void native_set_boost_data(JNIEnv* env, jclass,
        jobjectArray paths, jobjectArray values) {
    if (paths == nullptr || values == nullptr) return;
    jsize len = env->GetArrayLength(paths);
    if (len != env->GetArrayLength(values)) return;
    auto* hm = AxHintManager::getInstance();
    for (jsize i = 0; i < len; i++) {
        ScopedLocalRef<jstring> p(env,
                reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i)));
        ScopedLocalRef<jstring> v(env,
                reinterpret_cast<jstring>(env->GetObjectArrayElement(values, i)));
        if (p.get() != nullptr && v.get() != nullptr) {
            ScopedUtfChars path(env, p.get());
            ScopedUtfChars val(env, v.get());
            if (path.c_str() != nullptr && val.c_str() != nullptr) {
                hm->setBoostData(path.c_str(), val.c_str());
            }
        }
    }
}

static jboolean native_is_composition_boosting(JNIEnv*, jclass) {
    return AxHintManager::getInstance()->isCompositionBoosting() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_should_defer_pss(JNIEnv*, jclass) {
    return AxHintManager::getInstance()->shouldDeferPss() ? JNI_TRUE : JNI_FALSE;
}

static void native_set_thermal_ceiling(JNIEnv* env, jclass, jstring path, jint ceiling) {
    if (path == nullptr) return;
    ScopedUtfChars p(env, path);
    if (p.c_str() == nullptr) return;
    AxHintManager::getInstance()->setThermalCeiling(p.c_str(), ceiling);
}

static void native_remove_thermal_ceiling(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    ScopedUtfChars p(env, path);
    if (p.c_str() == nullptr) return;
    AxHintManager::getInstance()->removeThermalCeiling(p.c_str());
}

static void native_set_cpu_freq_bound(
        JNIEnv* env, jclass, jstring path, jint boundValue, jboolean isFloor) {
    if (path == nullptr) return;
    ScopedUtfChars p(env, path);
    if (p.c_str() == nullptr) return;
    AxHintManager::getInstance()->setCpuFreqBound(p.c_str(), boundValue, isFloor == JNI_TRUE);
}

static void native_remove_cpu_freq_bounds(JNIEnv*, jclass) {
    AxHintManager::getInstance()->removeCpuFreqBounds();
}

static void native_set_ui_boost_active(JNIEnv*, jclass, jboolean active) {
    AxHintManager::getInstance()->setUiBoostActive(active == JNI_TRUE);
}

static void native_update_top_app(JNIEnv*, jclass, jint pid, jint tid) {
    AxHintManager::getInstance()->updateTopApp(pid, tid);
}

static const JNINativeMethod sMethods[] = {
        {"native_perf_hint", "(IJ)J", reinterpret_cast<void*>(native_perf_hint)},
        {"native_perf_hint_rel", "(J)V", reinterpret_cast<void*>(native_perf_hint_rel)},
        {"native_set_boost_data", "([Ljava/lang/String;[Ljava/lang/String;)V",
                reinterpret_cast<void*>(native_set_boost_data)},
        {"native_is_composition_boosting", "()Z",
                reinterpret_cast<void*>(native_is_composition_boosting)},
        {"native_should_defer_pss", "()Z", reinterpret_cast<void*>(native_should_defer_pss)},
        {"native_set_thermal_ceiling", "(Ljava/lang/String;I)V",
                reinterpret_cast<void*>(native_set_thermal_ceiling)},
        {"native_remove_thermal_ceiling", "(Ljava/lang/String;)V",
                reinterpret_cast<void*>(native_remove_thermal_ceiling)},
        {"native_set_ui_boost_active", "(Z)V",
                reinterpret_cast<void*>(native_set_ui_boost_active)},
        {"native_set_cpu_freq_bound", "(Ljava/lang/String;IZ)V",
                reinterpret_cast<void*>(native_set_cpu_freq_bound)},
        {"native_remove_cpu_freq_bounds", "()V",
                reinterpret_cast<void*>(native_remove_cpu_freq_bounds)},
        {"native_update_top_app", "(II)V", reinterpret_cast<void*>(native_update_top_app)},
};

int register_android_server_am_AxPerformance(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/am/AxBoostManager",
            sMethods, NELEM(sMethods));
}

} // namespace android
