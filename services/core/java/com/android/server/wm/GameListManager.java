/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.wm;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import java.util.*;

class GameListManager {

    interface GameListChangeListener {
        void onGameListChanged();
    }

    private static final String GAME_LIST_KEY = "gamespace_game_list";
    private final List<GameListChangeListener> mListeners = new ArrayList<>();

    private final Context mContext;
    private final Map<String, String> mGameList = Collections.synchronizedMap(new HashMap<>());

    GameListManager(Context context) {
        this.mContext = context;
        loadGameList();
    }

    void loadGameList() {
        String raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                GAME_LIST_KEY, UserHandle.USER_CURRENT);
        Map<String, String> parsed = parseGameList(raw);
        synchronized (mGameList) {
            mGameList.clear();
            mGameList.putAll(parsed);
        }
        notifyListeners();
    }

    boolean isGame(String packageName) {
        synchronized (mGameList) {
            return mGameList.containsKey(packageName);
        }
    }

    boolean isGameInPerfMode(String packageName) {
        synchronized (mGameList) {
            return "2".equals(mGameList.get(packageName));
        }
    }

    void addGame(String packageName) {
        updateGameList(packageName, true);
    }

    void removeGame(String packageName) {
        updateGameList(packageName, false);
    }

    private void updateGameList(String packageName, boolean add) {
        ContentResolver cr = mContext.getContentResolver();
        String raw = Settings.System.getStringForUser(cr, GAME_LIST_KEY, UserHandle.USER_CURRENT);
        Map<String, String> gameMap = parseGameList(raw);

        boolean modified = false;
        if (add) {
            if (!"2".equals(gameMap.get(packageName))) {
                gameMap.put(packageName, "2");
                modified = true;
            }
        } else {
            if (gameMap.remove(packageName) != null) {
                modified = true;
            }
        }

        if (modified) {
            String updated = serializeGameMap(gameMap);
            Settings.System.putStringForUser(cr, GAME_LIST_KEY, updated, UserHandle.USER_CURRENT);
            synchronized (mGameList) {
                if (add) mGameList.put(packageName, "2");
                else mGameList.remove(packageName);
            }
            notifyListeners();
        }
    }

    void registerGameListObserver() {
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(GAME_LIST_KEY),
            false,
            new ContentObserver(new Handler(mContext.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    Slog.d("GameSpaceService", "Game list changed, reloading");
                    loadGameList();
                }
            },
            UserHandle.USER_ALL
        );
    }

    private Map<String, String> parseGameList(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;

        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=");
            if (parts.length == 2 &&
                parts[0].matches("[a-zA-Z0-9_.]+") &&
                parts[1].matches("\\d+")) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    private String serializeGameMap(Map<String, String> gameMap) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : gameMap.entrySet()) {
            entries.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(";", entries);
    }

    void addListener(GameListChangeListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    void removeListener(GameListChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void notifyListeners() {
        synchronized (mListeners) {
            for (GameListChangeListener listener : mListeners) {
                listener.onGameListChanged();
            }
        }
    }

}
