/*
 * Copyright 2025-2026 AxionOS
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

#include "AxHwuiMedia.h"

#include "Properties.h"

#include <android-base/file.h>
#include <android-base/properties.h>

#include <cstring>
#include <string>

namespace android::uirenderer {
namespace {

constexpr char kUseOpenGlForMediaProperty[] = "persist.sys.vk_use_ogl_for_media";
constexpr char kLauncherProcess[] = "com.android.launcher3";

std::string& currentPackageName() {
    static std::string packageName;
    return packageName;
}

std::string currentPackageOrProcessName() {
    const std::string& name = currentPackageName();
    if (!name.empty()) {
        return name;
    }

    static const std::string processName = [] {
        std::string cmdline;
        if (!base::ReadFileToString("/proc/self/cmdline", &cmdline)) {
            return std::string();
        }
        const size_t end = cmdline.find('\0');
        if (end != std::string::npos) {
            cmdline.resize(end);
        }
        return cmdline;
    }();
    return processName;
}

bool isLauncherProcess() {
    const std::string processName = currentPackageOrProcessName();
    const size_t launcherProcessSize = std::strlen(kLauncherProcess);
    return processName == kLauncherProcess ||
            (processName.size() > launcherProcessSize && processName[launcherProcessSize] == ':' &&
             processName.compare(0, launcherProcessSize, kLauncherProcess) == 0);
}

}

void AxHwuiMedia::setPackageName(const std::string& packageName) {
    currentPackageName() = packageName;
}

bool AxHwuiMedia::useOpenGlPipeline() {
    return base::GetBoolProperty(kUseOpenGlForMediaProperty, false) &&
            !Properties::isSystemOrPersistent && !isLauncherProcess();
}

}
