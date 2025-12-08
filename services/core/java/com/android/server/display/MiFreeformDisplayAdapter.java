/*
 * Copyright (C) 2025 AxionOS
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
package com.android.server.display;

import android.app.IFreeformDisplayCallback;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.display.feature.DisplayManagerFlags;

import java.util.ArrayList;
import java.util.List;

public class MiFreeformDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "MiFreeformDisplayAdapter";

    private final ArrayMap<IBinder, FreeformDisplayDevice> mFreeformDisplayDevices = new ArrayMap<>();
    private final LogicalDisplayMapper mLogicalDisplayMapper;

    public MiFreeformDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, 
            LogicalDisplayMapper logicalDisplayMapper, DisplayManagerFlags flags) {
        super(syncRoot, context, handler, listener, TAG, flags);
        mLogicalDisplayMapper = logicalDisplayMapper;
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
    }

    public void createFreeformLocked(String name, IFreeformDisplayCallback callback,
            int width, int height, int densityDpi, boolean secure,
            boolean ownContentOnly, boolean shouldShowSystemDecorations, Surface surface,
            float refreshRate, long presentationDeadlineNanos) {
        
        IBinder appToken = callback.asBinder();
        Slog.i(TAG, "createFreeformLocked: token=" + appToken + 
               ", ownContentOnly=" + ownContentOnly +
               ", shouldShowSystemDecorations=" + shouldShowSystemDecorations);
        
        if (mFreeformDisplayDevices.containsKey(appToken)) {
            Slog.w(TAG, "Display already exists for this token");
            return;
        }
        
        IBinder displayToken = DisplayControl.createVirtualDisplay(name, secure);
        FreeformDisplayDevice device = new FreeformDisplayDevice(displayToken, name, width, height,
                densityDpi, refreshRate, presentationDeadlineNanos,
                new FreeformFlags(secure, true /* ownContentOnly */, false /* shouldShowSystemDecorations */),
                surface, new Callback(callback, getHandler()), appToken);

        mFreeformDisplayDevices.put(appToken, device);
        
        sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
        
        try {
            appToken.linkToDeath(device, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link death recipient", e);
            mFreeformDisplayDevices.remove(appToken);
            device.destroyLocked(false);
            return;
        }
        
        getHandler().postDelayed(() -> {
            synchronized (getSyncRoot()) {
                LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
                Slog.i(TAG, "findLogicalDisplayForDevice: " + display);
                try {
                    if (display != null) {
                        device.notifyDisplayReady(display.getDisplayIdLocked());
                    } else {
                        Slog.e(TAG, "Failed to find logical display for device");
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error notifying display added", e);
                }
            }
        }, 500);
    }
    
    public void resizeFreeform(IBinder appToken, int width, int height, int densityDpi) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.get(appToken);
            if (device != null) {
                device.resizeLocked(width, height, densityDpi);
                Slog.i(TAG, "Resized freeform display: " + width + "x" + height + " @ " + densityDpi + "dpi");
            } else {
                Slog.w(TAG, "resizeFreeform: Device not found for token " + appToken);
            }
        }
    }
    
    public void releaseFreeform(IBinder appToken) {
        synchronized (getSyncRoot()) {
            Slog.i(TAG, "releaseFreeform: token=" + appToken + 
                   ", devices in map: " + mFreeformDisplayDevices.size());
            FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
            if (device != null) {
                device.destroyLocked(true);
                appToken.unlinkToDeath(device, 0);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
                Slog.i(TAG, "Released freeform display for token " + appToken);
            } else {
                Slog.w(TAG, "releaseFreeform: Device not found for token " + appToken +
                       ". Available tokens: " + mFreeformDisplayDevices.keySet());
            }
        }
    }
    
    private void handleBinderDiedLocked(IBinder appToken) {
        FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
        if (device != null) {
            Slog.w(TAG, "Client died, auto-releasing freeform display");
        }
    }
    
    public List<Integer> getAllFreeformDisplayIdsLocked() {
        List<Integer> displayIds = new ArrayList<>();
        for (FreeformDisplayDevice device : mFreeformDisplayDevices.values()) {
            LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display != null) {
                displayIds.add(display.getDisplayIdLocked());
            }
        }
        return displayIds;
    }
    
    public int getDisplayIdForToken(IBinder appToken) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.get(appToken);
            if (device != null) {
                LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
                if (display != null) {
                    return display.getDisplayIdLocked();
                }
            }
            return -1;
        }
    }
    
    public boolean isFreeformDisplayIdLocked(int displayId) {
        for (FreeformDisplayDevice device : mFreeformDisplayDevices.values()) {
            LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display != null && display.getDisplayIdLocked() == displayId) {
                return true;
            }
        }
        return false;
    }

    private class FreeformDisplayDevice extends DisplayDevice implements IBinder.DeathRecipient {
        private static final int PENDING_SURFACE_CHANGE = 0x01;
        private static final int PENDING_RESIZE = 0x02;
        
        private final String mName;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final FreeformFlags mFlags;
        private Surface mSurface;
        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private Display.Mode mMode;
        private final Callback mCallback;
        private final IBinder mAppToken;
        private DisplayDeviceInfo mInfo;
        private int mPendingChanges;

        public FreeformDisplayDevice(IBinder displayToken, String uniqueId,
                int width, int height, int density,
                float refreshRate, long presentationDeadlineNanos,
                FreeformFlags flags,
                Surface surface, Callback callback, IBinder appToken) {
            super(MiFreeformDisplayAdapter.this, displayToken, uniqueId, getContext());
            mName = uniqueId;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mFlags = flags;
            mSurface = surface;
            mWidth = width;
            mHeight = height;
            mDensityDpi = density;
            mMode = createMode(mWidth, mHeight, refreshRate);
            mCallback = callback;
            mAppToken = appToken;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
        }
        
        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                mWidth = width;
                mHeight = height;
                mDensityDpi = densityDpi;
                mMode = createMode(width, height, mRefreshRate);
                mInfo = null;
                mPendingChanges |= PENDING_RESIZE;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
            }
        }
        
        public void destroyLocked(boolean binderAlive) {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            DisplayControl.destroyVirtualDisplay(getDisplayTokenLocked());
            if (binderAlive) {
                mCallback.dispatchDisplayStopped();
            }
        }
        
        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                handleBinderDiedLocked(mAppToken);
                Slog.w(TAG, "Freeform display client died: " + mAppToken);
                destroyLocked(false);
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public void configureSurfaceLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_SURFACE_CHANGE) != 0) {
                setSurfaceLocked(t, mSurface);
                mPendingChanges &= ~PENDING_SURFACE_CHANGE;
            }
        }

        @Override
        public void configureDisplaySizeLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_RESIZE) != 0) {
                setDisplaySizeLocked(t, mWidth, mHeight);
                mPendingChanges &= ~PENDING_RESIZE;
            }
        }
         
        public void notifyDisplayReady(int displayId) {
            mCallback.obtainMessage(Callback.MSG_ON_DISPLAY_ADD, displayId, 0).sendToTarget();
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] { mMode };
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / (int) mRefreshRate;
                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.flags = 0;
                mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                mInfo.flags |= DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED;
                mInfo.flags |= DisplayDeviceInfo.FLAG_TRUSTED;
                mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
            }
            return mInfo;
        }
    }

    private static final class FreeformFlags {
        final boolean mSecure;
        final boolean mOwnContentOnly;
        final boolean mShouldShowSystemDecorations = false;

        FreeformFlags(boolean secure, boolean ownContentOnly, boolean shouldShowSystemDecorations) {
            mSecure = secure;
            mOwnContentOnly = ownContentOnly;
        }
    }

    private static class Callback extends Handler {
        private static final int MSG_ON_DISPLAY_ADD = 3;
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;

        private final IFreeformDisplayCallback mCallback;

        public Callback(IFreeformDisplayCallback callback, Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_DISPLAY_ADD:
                        mCallback.onDisplayAdd(msg.arg1);
                        break;
                    case MSG_ON_DISPLAY_PAUSED:
                        mCallback.onDisplayPaused();
                        break;
                    case MSG_ON_DISPLAY_RESUMED:
                        mCallback.onDisplayResumed();
                        break;
                    case MSG_ON_DISPLAY_STOPPED:
                        mCallback.onDisplayStopped();
                        break;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify callback", e);
            }
        }
        
        public void dispatchDisplayPaused() {
            sendEmptyMessage(MSG_ON_DISPLAY_PAUSED);
        }
        
        public void dispatchDisplayResumed() {
            sendEmptyMessage(MSG_ON_DISPLAY_RESUMED);
        }
        
        public void dispatchDisplayStopped() {
            sendEmptyMessage(MSG_ON_DISPLAY_STOPPED);
        }
    }
}
