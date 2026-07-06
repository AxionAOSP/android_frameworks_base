/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.server.kernel;

import android.content.ContentResolver;
import android.content.Context;
import android.os.AxKernelControl;
import android.os.AxKernelMetrics;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class AxKernelManagerService {
    private static final String TAG = "AxKernelManager";

    private static final String CPU_CLUSTER_COUNT_KEY = "ax_cpu_cluster_count";
    private static final String CPU_CLUSTER_FREQS_PREFIX = "ax_cpu_cluster_";
    private static final String CPU_CLUSTER_FREQS_SUFFIX = "_freqs";
    private static final String GPU_MIN_FREQ = "axion_gpu_min_freq";
    private static final String GPU_MAX_FREQ = "axion_gpu_max_freq";
    private static final String GPU_FREQS = "ax_gpu_freqs";
    private static final String CONFIG_NAME = "ax_kernel_manager.xml";
    private static final String TAG_CPU = "cpu";
    private static final String TAG_GPU = "gpu";
    private static final String ATTR_ID = "id";
    private static final String ATTR_MIN_ID = "minId";
    private static final String ATTR_MAX_ID = "maxId";
    private static final String ATTR_GOVERNOR_ID = "governorId";
    private static final String ATTR_GROUP = "group";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_NODE = "node";
    private static final String ATTR_MIN_PATH = "minPath";
    private static final String ATTR_MIN_NODE = "minNode";
    private static final String ATTR_MAX_PATH = "maxPath";
    private static final String ATTR_MAX_NODE = "maxNode";
    private static final String ATTR_AVAILABLE_PATH = "availablePath";
    private static final String ATTR_VALUES_NODE = "valuesNode";
    private static final String ATTR_GOVERNOR_PATH = "governorPath";
    private static final String ATTR_GOVERNOR_NODE = "governorNode";
    private static final String ATTR_GOVERNOR_AVAILABLE_PATH = "governorAvailablePath";
    private static final String ATTR_VALUES = "values";
    private static final String ATTR_RANGE = "range";
    private static final String ATTR_MIN = "min";
    private static final String ATTR_MAX = "max";
    private static final String ATTR_STEP = "step";
    private static final String ATTR_DEFAULT_MIN = "defaultMin";
    private static final String ATTR_DEFAULT_MAX = "defaultMax";
    private static final int MAX_CONFIG_VALUES = 256;
    private static final int PERSISTED_RESTORE_PASSES = 2;
    private static final String FREQ_PROP_PREFIX = "persist.sys.km.";

    private static final File[] CONFIG_FILES = {
            Environment.buildPath(Environment.getSystemExtDirectory(), "etc", CONFIG_NAME),
            Environment.buildPath(Environment.getVendorDirectory(), "etc", CONFIG_NAME),
    };

    private final Object mLock = new Object();
    private final ContentResolver mResolver;
    private final ArrayMap<String, NodeControl> mControls = new ArrayMap<>();
    private final AxKernelMetricsReader mMetricsReader = new AxKernelMetricsReader();

    public AxKernelManagerService(Context context) {
        mResolver = context.getContentResolver();
    }

    public void systemReady() {
        for (int i = 0; i < PERSISTED_RESTORE_PASSES; i++) {
            refreshControls();
            applyPersistedValues();
        }
    }

    public List<AxKernelControl> getControls() {
        ArrayList<NodeControl> controls;
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                refreshControlsLocked();
                controls = new ArrayList<>(mControls.values());
            }
            ArrayList<AxKernelControl> snapshots = new ArrayList<>(controls.size());
            for (NodeControl control : controls) {
                AxKernelControl snapshot = control.snapshot(mResolver);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
            return snapshots;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public boolean setControlValue(String id, int value) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        NodeControl control;
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                refreshControlsLocked();
                control = mControls.get(id);
            }
            if (control == null) {
                return false;
            }
            int resolvedValue = control.coerce(value);
            FileUtils.stringToFile(control.path, control.toFileValue(resolvedValue));
            Settings.Secure.putIntForUser(mResolver, control.id, resolvedValue,
                    UserHandle.USER_CURRENT);
            synchronized (mLock) {
                refreshControlsLocked();
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Slog.w(TAG, "Failed to set " + id, e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public AxKernelMetrics getMetrics(long previousCpuActiveTimeTicks, long previousCpuTimeTicks) {
        long token = Binder.clearCallingIdentity();
        try {
            return mMetricsReader.query(previousCpuActiveTimeTicks, previousCpuTimeTicks);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void refreshControls() {
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                refreshControlsLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void refreshControlsLocked() {
        ArrayList<NodeControl> controls = new ArrayList<>();
        AxKernelMetricsReader.Config metricsConfig = new AxKernelMetricsReader.Config();
        findConfiguredControls(controls, metricsConfig);
        mControls.clear();
        for (NodeControl control : controls) {
            mControls.put(control.id, control);
        }
        mMetricsReader.setConfig(metricsConfig);
        publishMetadata(controls);
    }

    private void applyPersistedValues() {
        ArrayList<NodeControl> controls;
        synchronized (mLock) {
            controls = new ArrayList<>(mControls.values());
        }
        for (NodeControl control : controls) {
            int value = Settings.Secure.getIntForUser(mResolver, control.id,
                    Integer.MIN_VALUE, UserHandle.USER_CURRENT);
            if (value == Integer.MIN_VALUE || !canUseNode(control.path)) {
                continue;
            }
            try {
                int resolvedValue = control.coerce(value);
                FileUtils.stringToFile(control.path, control.toFileValue(resolvedValue));
            } catch (IOException | IllegalArgumentException e) {
                Slog.w(TAG, "Failed to restore " + control.id, e);
            }
        }
    }

    private void findConfiguredControls(ArrayList<NodeControl> controls,
            AxKernelMetricsReader.Config metricsConfig) {
        for (File file : CONFIG_FILES) {
            if (!file.isFile()) {
                continue;
            }
            try (FileInputStream stream = new FileInputStream(file)) {
                readConfig(Xml.resolvePullParser(stream), controls, metricsConfig);
            } catch (IOException | XmlPullParserException e) {
                Slog.w(TAG, "Failed to read " + file, e);
            }
        }
    }

    private void readConfig(TypedXmlPullParser parser, ArrayList<NodeControl> controls,
            AxKernelMetricsReader.Config metricsConfig)
            throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }
        if (type == XmlPullParser.END_DOCUMENT) {
            return;
        }
        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            String tag = parser.getName();
            if (TAG_CPU.equals(tag)) {
                metricsConfig.addCpu(parser);
                addConfiguredCpu(controls, parser);
            } else if (TAG_GPU.equals(tag)) {
                metricsConfig.addGpu(parser);
                addConfiguredPair(controls, parser, GPU_MIN_FREQ, GPU_MAX_FREQ, "gpu",
                        AxKernelControl.TYPE_GPU_MIN_FREQ, AxKernelControl.TYPE_GPU_MAX_FREQ);
            }
        }
    }

    private boolean addConfiguredCpu(ArrayList<NodeControl> controls, TypedXmlPullParser parser) {
        String id = parser.getAttributeValue(null, ATTR_ID);
        String base = pathAttr(parser, ATTR_PATH, ATTR_NODE);
        String group = attrOrDefault(parser, ATTR_GROUP, id);
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(group)) {
            return false;
        }
        String minId = attrOrDefault(parser, ATTR_MIN_ID, id + "_min_freq");
        String maxId = attrOrDefault(parser, ATTR_MAX_ID, id + "_max_freq");
        if (!addConfiguredPair(controls, parser, minId, maxId, group,
                AxKernelControl.TYPE_CPU_MIN_FREQ, AxKernelControl.TYPE_CPU_MAX_FREQ)) {
            return false;
        }
        String governorPath = pathAttr(parser, ATTR_GOVERNOR_PATH, ATTR_GOVERNOR_NODE);
        if (TextUtils.isEmpty(governorPath) && !TextUtils.isEmpty(base)) {
            governorPath = base + "/scaling_governor";
        }
        if (!TextUtils.isEmpty(governorPath) && canUseNode(governorPath)) {
            String availablePath = parser.getAttributeValue(null, ATTR_GOVERNOR_AVAILABLE_PATH);
            if (TextUtils.isEmpty(availablePath) && !TextUtils.isEmpty(base)) {
                availablePath = base + "/scaling_available_governors";
            }
            String[] governors = !TextUtils.isEmpty(availablePath)
                    ? readStringList(new File(availablePath)) : new String[0];
            if (governors.length > 0) {
                int defaultGovernor = stringIndex(governors, readString(governorPath), 0);
                controls.add(new NodeControl(attrOrDefault(parser, ATTR_GOVERNOR_ID,
                        id + "_governor"), group, AxKernelControl.TYPE_CPU_GOVERNOR,
                        governorPath, defaultGovernor, indexedValues(governors.length),
                        governors, governors));
            }
        }
        return true;
    }

    private boolean addConfiguredPair(ArrayList<NodeControl> controls, TypedXmlPullParser parser,
            String minId, String maxId, String fallbackGroup, int minType, int maxType) {
        String base = pathAttr(parser, ATTR_PATH, ATTR_NODE);
        String minPath = pathAttr(parser, ATTR_MIN_PATH, ATTR_MIN_NODE);
        String maxPath = pathAttr(parser, ATTR_MAX_PATH, ATTR_MAX_NODE);
        if (TextUtils.isEmpty(base) && (TextUtils.isEmpty(minPath) || TextUtils.isEmpty(maxPath))) {
            return false;
        }
        if (TextUtils.isEmpty(minPath)) {
            minPath = base + "/min_freq";
        }
        if (TextUtils.isEmpty(maxPath)) {
            maxPath = base + "/max_freq";
        }
        int[] available = readConfiguredValues(parser, base);
        if (available.length == 0 || !canUseNode(minPath) || !canUseNode(maxPath)) {
            return false;
        }
        String group = attrOrDefault(parser, ATTR_GROUP, fallbackGroup);
        if (TextUtils.isEmpty(group)) {
            group = new File(base).getName();
        }
        int defaultMin = parser.getAttributeInt(null, ATTR_DEFAULT_MIN, available[0]);
        int defaultMax = parser.getAttributeInt(null, ATTR_DEFAULT_MAX,
                available[available.length - 1]);
        controls.add(new NodeControl(minId, group, minType, minPath, defaultMin, available));
        controls.add(new NodeControl(maxId, group, maxType, maxPath, defaultMax, available));
        return true;
    }

    private static String attrOrDefault(TypedXmlPullParser parser, String attr, String defaultValue) {
        String value = parser.getAttributeValue(null, attr);
        return TextUtils.isEmpty(value) ? defaultValue : value;
    }

    private static String pathAttr(TypedXmlPullParser parser, String attr, String alias) {
        String value = parser.getAttributeValue(null, attr);
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        return parser.getAttributeValue(null, alias);
    }

    private static int[] readConfiguredValues(TypedXmlPullParser parser, String base) {
        int[] values = parseIntList(parser.getAttributeValue(null, ATTR_VALUES));
        if (values.length > 0) {
            return values;
        }
        String availablePath = pathAttr(parser, ATTR_AVAILABLE_PATH, ATTR_VALUES_NODE);
        if (!TextUtils.isEmpty(availablePath)) {
            values = readIntList(new File(availablePath));
            if (values.length > 0) {
                return values;
            }
        }
        if (!TextUtils.isEmpty(base)) {
            values = readIntList(new File(base, "available_frequencies"));
            if (values.length == 0) {
                values = readIntList(new File(base, "available_freqs"));
            }
            if (values.length > 0) {
                return values;
            }
        }
        values = parseRange(parser.getAttributeValue(null, ATTR_RANGE));
        if (values.length > 0) {
            return values;
        }
        int min = parser.getAttributeInt(null, ATTR_MIN, Integer.MIN_VALUE);
        int max = parser.getAttributeInt(null, ATTR_MAX, Integer.MIN_VALUE);
        int step = parser.getAttributeInt(null, ATTR_STEP, 1);
        if (min != Integer.MIN_VALUE && max != Integer.MIN_VALUE && max >= min && step > 0) {
            return rangeValues(min, max, step);
        }
        return new int[0];
    }

    private static boolean canUseNode(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        return file.isFile() && file.canRead() && file.canWrite();
    }

    private static String[] splitNodeValues(String value) {
        if (TextUtils.isEmpty(value)) {
            return new String[0];
        }
        String[] tokens = value.split("[\\s,]+");
        ArrayList<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (!token.isEmpty()) {
                values.add(token);
            }
        }
        return values.toArray(new String[0]);
    }

    private static int[] parseIntList(String value) {
        if (TextUtils.isEmpty(value)) {
            return new int[0];
        }
        TreeSet<Integer> values = new TreeSet<>();
        for (String token : value.split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(token));
                if (values.size() > MAX_CONFIG_VALUES) {
                    return new int[0];
                }
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return toArray(values);
    }

    private static int[] parseRange(String value) {
        if (TextUtils.isEmpty(value)) {
            return new int[0];
        }
        String[] parts = value.trim().split(":", 2);
        String range = parts[0].trim();
        int separator = range.indexOf("..");
        int separatorLength = 2;
        if (separator < 0) {
            separator = range.indexOf('-');
            separatorLength = 1;
        }
        if (separator <= 0 || separator + separatorLength >= range.length()) {
            return new int[0];
        }
        try {
            int min = Integer.parseInt(range.substring(0, separator).trim());
            int max = Integer.parseInt(range.substring(separator + separatorLength).trim());
            int step = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
            if (max < min || step <= 0) {
                return new int[0];
            }
            return rangeValues(min, max, step);
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }

    private static int[] rangeValues(int min, int max, int step) {
        long count = (((long) max - min) / step) + 1;
        if (count <= 0 || count > MAX_CONFIG_VALUES) {
            return new int[0];
        }
        int[] values = new int[(int) count];
        for (int i = 0; i < count; i++) {
            values[i] = min + (i * step);
        }
        return values;
    }

    private static int[] indexedValues(int count) {
        if (count <= 0 || count > MAX_CONFIG_VALUES) {
            return new int[0];
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = i;
        }
        return values;
    }

    private void publishMetadata(ArrayList<NodeControl> controls) {
        ArrayMap<String, int[]> cpuFreqs = new ArrayMap<>();
        int[] gpuFreqs = null;
        for (NodeControl control : controls) {
            if (control.type == AxKernelControl.TYPE_CPU_MIN_FREQ) {
                cpuFreqs.put(control.group, control.availableValues);
            } else if (control.type == AxKernelControl.TYPE_GPU_MIN_FREQ) {
                gpuFreqs = control.availableValues;
            }
        }
        Settings.Secure.putIntForUser(mResolver, CPU_CLUSTER_COUNT_KEY, cpuFreqs.size(),
                UserHandle.USER_CURRENT);
        for (int i = 0; i < cpuFreqs.size(); i++) {
            int[] freqs = cpuFreqs.valueAt(i);
            Settings.Secure.putStringForUser(mResolver, clusterFreqsKey(i), join(freqs),
                    UserHandle.USER_CURRENT);
            if (i == 0) {
                Settings.Secure.putStringForUser(mResolver, "ax_cpu_small_freqs", join(freqs),
                        UserHandle.USER_CURRENT);
            } else if (i == 1) {
                Settings.Secure.putStringForUser(mResolver, "ax_cpu_big_freqs", join(freqs),
                        UserHandle.USER_CURRENT);
            } else if (i == cpuFreqs.size() - 1) {
                Settings.Secure.putStringForUser(mResolver, "ax_cpu_prime_freqs", join(freqs),
                        UserHandle.USER_CURRENT);
            }
        }
        if (gpuFreqs != null) {
            Settings.Secure.putStringForUser(mResolver, GPU_FREQS, join(gpuFreqs),
                    UserHandle.USER_CURRENT);
        }
    }

    public static String propKeyForPath(String path) {
        int hash = 0;
        for (int i = 0; i < path.length(); i++) {
            hash = 31 * hash + path.charAt(i);
        }
        return FREQ_PROP_PREFIX + Integer.toHexString(hash);
    }

    private static int[] readIntList(File file) {
        try {
            String text = FileUtils.readTextFile(file, 0, null).trim();
            if (text.isEmpty()) {
                return new int[0];
            }
            TreeSet<Integer> values = new TreeSet<>();
            for (String token : text.split("[\\s,]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                values.add(Integer.parseInt(token));
            }
            return toArray(values);
        } catch (IOException | NumberFormatException e) {
            return new int[0];
        }
    }

    private static String[] readStringList(File file) {
        try {
            String text = FileUtils.readTextFile(file, 0, null).trim();
            return splitNodeValues(text);
        } catch (IOException e) {
            return new String[0];
        }
    }

    private static int stringIndex(String[] values, String value, int defaultValue) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return defaultValue;
    }

    private static int[] toArray(TreeSet<Integer> values) {
        int[] result = new int[values.size()];
        int i = 0;
        for (int value : values) {
            result[i++] = value;
        }
        return result;
    }

    private static int readInt(String path, int defaultValue) {
        try {
            String text = FileUtils.readTextFile(new File(path), 128, null).trim();
            if (text.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(text.split("[\\s,]+", 2)[0]);
        } catch (IOException | NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readString(String path) {
        try {
            String text = FileUtils.readTextFile(new File(path), 256, null).trim();
            if (text.isEmpty()) {
                return "";
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String clusterFreqsKey(int index) {
        return CPU_CLUSTER_FREQS_PREFIX + index + CPU_CLUSTER_FREQS_SUFFIX;
    }

    private static String join(int[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static final class NodeControl {
        final String id;
        final String group;
        final int type;
        final String path;
        final int defaultValue;
        final int[] availableValues;
        final String[] valueLabels;
        final String[] writeValues;

        NodeControl(String id, String group, int type, String path, int defaultValue,
                int[] availableValues) {
            this(id, group, type, path, defaultValue, availableValues, null, null);
        }

        NodeControl(String id, String group, int type, String path, int defaultValue,
                int[] availableValues, String[] valueLabels, String[] writeValues) {
            this.id = Objects.requireNonNull(id);
            this.group = Objects.requireNonNull(group);
            this.type = type;
            this.path = Objects.requireNonNull(path);
            this.defaultValue = defaultValue;
            this.availableValues = availableValues.clone();
            this.valueLabels = valueLabels != null ? valueLabels.clone() : new String[0];
            this.writeValues = writeValues != null ? writeValues.clone() : new String[0];
        }

        AxKernelControl snapshot(ContentResolver resolver) {
            if (!canUseNode(path)) {
                return null;
            }
            return new AxKernelControl(id, group, type, readSnapshotValue(resolver), defaultValue,
                    availableValues, valueLabels);
        }

        int readSnapshotValue(ContentResolver resolver) {
            int value = Settings.Secure.getIntForUser(resolver, id, Integer.MIN_VALUE,
                    UserHandle.USER_CURRENT);
            return value != Integer.MIN_VALUE ? coerce(value) : readValue();
        }

        boolean isFrequency() {
            return type == AxKernelControl.TYPE_CPU_MIN_FREQ
                    || type == AxKernelControl.TYPE_CPU_MAX_FREQ
                    || type == AxKernelControl.TYPE_GPU_MIN_FREQ
                    || type == AxKernelControl.TYPE_GPU_MAX_FREQ;
        }

        int readValue() {
            if (writeValues.length == 0) {
                return readInt(path, defaultValue);
            }
            String value = readString(path);
            for (int i = 0; i < writeValues.length && i < availableValues.length; i++) {
                if (writeValues[i].equals(value)) {
                    return availableValues[i];
                }
            }
            return defaultValue;
        }

        int coerce(int value) {
            if (availableValues.length == 0) {
                return value;
            }
            int best = availableValues[0];
            int distance = Math.abs(value - best);
            for (int i = 1; i < availableValues.length; i++) {
                int nextDistance = Math.abs(value - availableValues[i]);
                if (nextDistance < distance) {
                    best = availableValues[i];
                    distance = nextDistance;
                }
            }
            return best;
        }

        String toFileValue(int value) {
            if (writeValues.length == 0) {
                return Integer.toString(value);
            }
            for (int i = 0; i < availableValues.length && i < writeValues.length; i++) {
                if (availableValues[i] == value) {
                    return writeValues[i];
                }
            }
            return writeValues[0];
        }

    }
}
