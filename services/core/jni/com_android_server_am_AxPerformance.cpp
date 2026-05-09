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

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

#include <utils/Log.h>

#include <fcntl.h>
#include <linux/time.h>
#include <pthread.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdlib>
#include <condition_variable>
#include <fstream>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <processgroup/processgroup.h>

namespace android {

struct NodeAction {
    std::string path;
    std::string upValue;
    std::string downValue;
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
    OP_GAME_LAUNCH_BOOST = 69,
};

class AxHintManager {
public:
    int mRtPid = 0;
    int mRtTid = 0;

    static AxHintManager* getInstance() {
        static AxHintManager instance;
        return &instance;
    }

    void setBoostData(const std::string& path, const std::string& value) {
        std::lock_guard<std::mutex> lock(mMutex);
        mRestoreValues[path] = value;
        if (mResourceRefs.find(path) != mResourceRefs.end()) return;
        writeClamped(path, value);
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
        std::string value = std::to_string(ceiling);
        if (writeSysfs(path, value)) mCurrentValues[path] = value;
    }

    void removeThermalCeiling(const std::string& path) {
        std::lock_guard<std::mutex> lock(mMutex);
        mThermalCeilings.erase(path);
        if (mResourceRefs.find(path) != mResourceRefs.end()) {
            restoreLatestValueLocked(path, -1);
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

    void releaseOpcodeLocked(int opcode) {
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

    int createHandle(int opcode, int64_t durMs) {
        std::lock_guard<std::mutex> lock(mMutex);
        return createHandleLocked(opcode, durMs);
    }

    int createHandleLocked(int opcode, int64_t durMs) {
        if (opcode <= 0 || opcode >= mHintCount || mHints[opcode].nodes.empty()) return -1;
        if (durMs == 0) {
            releaseOpcodeLocked(opcode);
            return -1;
        }
        HintEntry& entry = mHints[opcode];
        int64_t now = nowMs();
        int64_t timeoutMs = (durMs == -2) ? entry.defaultTimeoutMs : (durMs > 0 ? durMs : 0);
        int64_t expiry = (timeoutMs > 0) ? now + timeoutMs : 0;
        if (mOpcodeHandles[opcode] > 0) {
            int handle = mOpcodeHandles[opcode];
            auto hit = mHandles.find(handle);
            if (hit != mHandles.end()) {
                releaseHandleLocked(handle);
                mLastHintMs[opcode] = 0;
            } else {
                mOpcodeHandles[opcode] = 0;
            }
        }
        int64_t cooldown = (entry.defaultTimeoutMs > 0) ? entry.defaultTimeoutMs / 2 : kCoalesceMs;
        if (now < mLastHintMs[opcode] + cooldown) return -1;
        int handle = nextHandleLocked();
        if (handle < 0) return -1;
        std::vector<std::pair<std::string, std::string>> applied;
        for (auto& node : entry.nodes) {
            auto rit = mResourceRefs.find(node.path);
            if (rit == mResourceRefs.end()
                    && mRestoreValues.find(node.path) == mRestoreValues.end()) {
                std::string orig = readSysfs(node.path);
                if (!orig.empty()) mOriginalValues[node.path] = orig;
            }
            mResourceRefs[node.path]++;
            writeClamped(node.path, node.upValue);
            applied.push_back({node.path, node.upValue});
        }
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
                opcode, expiry, cgroupPid, cgroupTid, nextSequenceLocked(), std::move(applied)};
        mOpcodeHandles[opcode] = handle;
        if (expiry > 0) mExpiries[opcode] = expiry;
        if (shouldNotifyTimerLocked(expiry, handle)) mCond.notify_one();
        return handle;
    }

    void releaseHandle(int handle) {
        std::lock_guard<std::mutex> lock(mMutex);
        releaseHandleLocked(handle);
    }

    void releaseHandleLocked(int handle) {
        auto hit = mHandles.find(handle);
        if (hit == mHandles.end()) return;
        if (hit->second.opcode >= 0 && hit->second.opcode < mHintCount
                && hit->second.cgroupTid > 0) {
            HintEntry& entry = mHints[hit->second.opcode];
            if (!entry.upCgroup.empty()) {
                releaseTaskProfileLocked(hit->second.cgroupPid, entry.upCgroup,
                        entry.downCgroup, handle);
                releaseTaskProfileLocked(hit->second.cgroupTid, entry.upCgroup,
                        entry.downCgroup, handle);
            }
        }
        for (auto& [path, val] : hit->second.applied) {
            auto rit = mResourceRefs.find(path);
            if (rit != mResourceRefs.end()) {
                rit->second--;
                if (rit->second <= 0) {
                    mResourceRefs.erase(rit);
                    auto bit = mRestoreValues.find(path);
                    if (bit != mRestoreValues.end()) {
                        writeClamped(path, bit->second);
                    } else {
                        auto oit = mOriginalValues.find(path);
                        if (oit != mOriginalValues.end()) {
                            writeClamped(path, oit->second);
                        }
                    }
                    mOriginalValues.erase(path);
                } else {
                    restoreLatestValueLocked(path, handle);
                }
            }
        }
        if (hit->second.opcode >= 0 && hit->second.opcode < mHintCount) {
            mExpiries[hit->second.opcode] = 0;
            if (mOpcodeHandles[hit->second.opcode] == handle) {
                mOpcodeHandles[hit->second.opcode] = 0;
            }
        }
        mHandles.erase(hit);
    }

    int createPerfLockHandle(
            int64_t durMs, std::vector<std::pair<std::string, std::string>>&& requested) {
        std::lock_guard<std::mutex> lock(mMutex);
        int handle = nextHandleLocked();
        if (handle < 0) return -1;
        std::vector<std::pair<std::string, std::string>> applied;
        applied.reserve(requested.size());
        for (const auto& [path, value] : requested) {
            auto rit = mResourceRefs.find(path);
            if (rit == mResourceRefs.end()
                    && mRestoreValues.find(path) == mRestoreValues.end()) {
                std::string orig = readSysfs(path);
                if (!orig.empty()) mOriginalValues[path] = orig;
            }
            mResourceRefs[path]++;
            writeClamped(path, value);
            applied.push_back({path, value});
        }
        int64_t expiry = (durMs > 0) ? nowMs() + durMs : 0;
        mHandles[handle] = {-1, expiry, 0, 0, nextSequenceLocked(), std::move(applied)};
        if (shouldNotifyTimerLocked(expiry, handle)) mCond.notify_one();
        return handle;
    }

    void releaseAllHandles() {
        std::lock_guard<std::mutex> lock(mMutex);
        std::vector<int> handles;
        handles.reserve(mHandles.size());
        for (const auto& [handle, _] : mHandles) {
            handles.push_back(handle);
        }
        for (int handle : handles) {
            releaseHandleLocked(handle);
        }
    }

    void setUiBoostActive(bool active) {
        std::lock_guard<std::mutex> lock(mMutex);
        mUiBoostActive = active;
    }
    bool isUiBoostActive() const { return mUiBoostActive; }

    void writeClamped(const std::string& path, const std::string& value) {
        std::string clamped = value;
        char* end = nullptr;
        int val = strtol(value.c_str(), &end, 10);
        bool validInt = (end != value.c_str() && *end == '\0');
        if (validInt) {
            auto it = mThermalCeilings.find(path);
            if (it != mThermalCeilings.end() && val > it->second) {
                val = it->second;
                clamped = std::to_string(val);
            }
            auto bit = mBounds.find(path);
            if (bit != mBounds.end()) {
                if (bit->second.isFloor && val < bit->second.value) {
                    val = bit->second.value;
                    clamped = std::to_string(val);
                } else if (!bit->second.isFloor && val > bit->second.value) {
                    val = bit->second.value;
                    clamped = std::to_string(val);
                }
            }
        }
        auto cur = mCurrentValues.find(path);
        if (cur != mCurrentValues.end() && cur->second == clamped) return;
        if (writeSysfs(path, clamped)) mCurrentValues[path] = clamped;
    }

    AxHintManager() : mRunning(true), mTimerThread(&AxHintManager::timerLoop, this) {
        std::unordered_map<int, ResourceEntry> resMap;
        loadResources("/vendor/etc/ax_perf_resources.xml", resMap);
        if (resMap.empty()) loadResources("/system/etc/ax_perf_resources.xml", resMap);
        loadBoosts("/vendor/etc/ax_perf_boosts.xml", resMap);
        if (mHints.empty()) loadBoosts("/system/etc/ax_perf_boosts.xml", resMap);
        mHintCount = mHints.size();
        mLastHintMs.resize(mHintCount, 0);
        mExpiries.resize(mHintCount, 0);
        mOpcodeHandles.resize(mHintCount, 0);
        pthread_setname_np(mTimerThread.native_handle(), "AxTimerLoop");
        pid_t timerTid = pthread_gettid_np(mTimerThread.native_handle());
        SetTaskProfiles(timerTid, {"ProcessCapacityLow"});
    }

    ~AxHintManager() {
        mRunning = false;
        mCond.notify_all();
        if (mTimerThread.joinable()) mTimerThread.join();
        for (auto& kv : mFdCache) {
            if (kv.second >= 0) close(kv.second);
        }
        mFdCache.clear();
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

    bool writeSysfs(const std::string& path, const std::string& value) {
        int fd = -1;
        auto it = mFdCache.find(path);
        if (it != mFdCache.end()) {
            fd = it->second;
        } else {
            fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
            if (fd < 0) return false;
            mFdCache[path] = fd;
        }
        ssize_t n = write(fd, value.c_str(), value.size());
        if (n < 0 && (errno == EBADF || errno == EIO)) {
            mFdCache.erase(path);
            close(fd);
            fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
            if (fd < 0) return false;
            mFdCache[path] = fd;
            n = write(fd, value.c_str(), value.size());
        }
        return n == static_cast<ssize_t>(value.size());
    }

    static std::string readSysfs(const std::string& path) {
        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) return "";
        char buf[256] = {};
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
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
            actions.push_back({std::stoi(idStr, nullptr, 16), attr(chunk, "value")});
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
            std::string rpath = attr(chunk, "path");
            std::string def = attr(chunk, "default");
            if (idStr.empty() || rpath.empty()) continue;
            int id = std::stoi(idStr, nullptr, 16);
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
            int brId = std::stoi(idStr, nullptr, 16);
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

            int opcode = std::stoi(attr(header, "opcode"));
            int64_t timeout = std::stoll(attr(header, "timeout_ms"));
            std::string upCgroup = attr(header, "uc");
            std::string downCgroup = attr(header, "dc");
            std::vector<NodeAction> nodes;

            for (auto& [rid, parsedValue] : parseSetActions(body)) {
                auto rit = resMap.find(rid);
                if (rit != resMap.end()) {
                    std::string val = parsedValue.empty() ? rit->second.defaultVal : parsedValue;
                    nodes.push_back({rit->second.path, val, rit->second.defaultVal});
                } else if (parsedValue.empty()) {
                    auto brIt = brMap.find(rid);
                    if (brIt != brMap.end()) {
                        for (auto& [subId, subVal] : brIt->second) {
                            auto subRit = resMap.find(subId);
                            if (subRit != resMap.end()) {
                                std::string sv = subVal.empty() ? subRit->second.defaultVal : subVal;
                                nodes.push_back({subRit->second.path, sv, subRit->second.defaultVal});
                            }
                        }
                    }
                }
            }

            if (opcode > 0 && !nodes.empty()) {
                hintsNew[opcode] = {nodes, timeout, upCgroup, downCgroup};
                if (opcode > maxOpcode) maxOpcode = opcode;
            }
        }

        if (!hintsNew.empty()) {
            mHints.resize(maxOpcode + 1);
            mLastHintMs.resize(maxOpcode + 1, 0);
            mExpiries.resize(maxOpcode + 1, 0);
            for (auto& kv : hintsNew) {
                mHints[kv.first] = kv.second;
            }
            mHintCount = maxOpcode + 1;
            mOpcodeHandles.resize(mHintCount, 0);
        }
    }

public:
    std::vector<HintEntry> mHints;
    std::vector<int64_t> mLastHintMs;
    std::vector<int64_t> mExpiries;
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
    std::unordered_map<std::string, std::string> mOriginalValues;
    std::unordered_map<std::string, std::string> mCurrentValues;
    std::unordered_map<std::string, int> mFdCache;
    mutable std::mutex mMutex;
    std::condition_variable mCond;
    std::atomic<bool> mRunning;
    std::thread mTimerThread;
    static constexpr int64_t kCoalesceMs = 50;
    static constexpr int64_t kProcessPssDeferMs = 2500;

private:
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
        SetTaskProfiles(taskId, std::vector<std::string>{profile});
        mTaskProfiles[taskId] = profile;
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
                || opcode == OP_GAME_LAUNCH_BOOST;
    }

    void restoreLatestValueLocked(const std::string& path, int releasedHandle) {
        uint64_t latestSequence = 0;
        std::string latestValue;
        for (const auto& [handle, entry] : mHandles) {
            if (handle == releasedHandle) continue;
            for (const auto& [appliedPath, appliedValue] : entry.applied) {
                if (appliedPath == path && entry.sequence >= latestSequence) {
                    latestSequence = entry.sequence;
                    latestValue = appliedValue;
                }
            }
        }
        if (latestSequence > 0) writeClamped(path, latestValue);
    }
};

static jlong native_perf_hint(JNIEnv*, jclass, jint opcode, jlong durMs) {
    auto* hm = AxHintManager::getInstance();
    if (opcode < 0 || opcode >= hm->mHintCount) return -1;
    return (jlong)hm->createHandle(opcode, durMs);
}

static void native_perf_hint_rel(JNIEnv*, jclass, jlong handle) {
    AxHintManager::getInstance()->releaseHandle((int)handle);
}

static void native_set_boost_data(JNIEnv* env, jclass,
        jobjectArray paths, jobjectArray values) {
    jsize len = env->GetArrayLength(paths);
    if (len != env->GetArrayLength(values)) return;
    auto* hm = AxHintManager::getInstance();
    for (jsize i = 0; i < len; i++) {
        jstring p = (jstring)env->GetObjectArrayElement(paths, i);
        jstring v = (jstring)env->GetObjectArrayElement(values, i);
        if (p != nullptr && v != nullptr) {
            ScopedUtfChars path(env, p);
            ScopedUtfChars val(env, v);
            hm->setBoostData(path.c_str(), val.c_str());
        }
        if (p != nullptr) env->DeleteLocalRef(p);
        if (v != nullptr) env->DeleteLocalRef(v);
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

static void native_set_cpu_freq_bound(JNIEnv* env, jclass, jstring path, jint boundValue, jboolean isFloor) {
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
    {"native_perf_hint", "(IJ)J", (void*)native_perf_hint},
    {"native_perf_hint_rel", "(J)V", (void*)native_perf_hint_rel},
    {"native_set_boost_data", "([Ljava/lang/String;[Ljava/lang/String;)V", (void*)native_set_boost_data},
    {"native_is_composition_boosting", "()Z", (void*)native_is_composition_boosting},
    {"native_should_defer_pss", "()Z", (void*)native_should_defer_pss},
    {"native_set_thermal_ceiling", "(Ljava/lang/String;I)V", (void*)native_set_thermal_ceiling},
    {"native_remove_thermal_ceiling", "(Ljava/lang/String;)V", (void*)native_remove_thermal_ceiling},
    {"native_set_ui_boost_active", "(Z)V", (void*)native_set_ui_boost_active},
    {"native_set_cpu_freq_bound", "(Ljava/lang/String;IZ)V", (void*)native_set_cpu_freq_bound},
    {"native_remove_cpu_freq_bounds", "()V", (void*)native_remove_cpu_freq_bounds},
    {"native_update_top_app", "(II)V", (void*)native_update_top_app},
};

int register_android_server_am_AxPerformance(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/am/AxBoostManager",
            sMethods, NELEM(sMethods));
}

} // namespace android
