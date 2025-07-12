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
    private static final String GAME_LIST_KEY = "gamespace_game_list";

    private final Context mContext;
    private final Set<String> mGameList = Collections.synchronizedSet(new HashSet<>());

    GameListManager(Context context) {
        this.mContext = context;
        loadGameList();
    }

    void loadGameList() {
        String raw = Settings.System.getStringForUser(mContext.getContentResolver(), GAME_LIST_KEY, UserHandle.USER_CURRENT);
        Map<String, String> parsed = parseGameList(raw);
        synchronized (mGameList) {
            mGameList.clear();
            mGameList.addAll(parsed.keySet());
        }
    }

    boolean isGame(String packageName) {
        synchronized (mGameList) {
            return mGameList.contains(packageName);
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

        boolean modified = add ? gameMap.putIfAbsent(packageName, "2") == null : gameMap.remove(packageName) != null;
        if (modified) {
            String updated = serializeGameMap(gameMap);
            Settings.System.putStringForUser(cr, GAME_LIST_KEY, updated, UserHandle.USER_CURRENT);
            if (add) mGameList.add(packageName);
            else mGameList.remove(packageName);
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
            if (parts.length == 2 && parts[0].matches("[a-zA-Z0-9_.]+") && parts[1].matches("\\d+")) {
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
}
