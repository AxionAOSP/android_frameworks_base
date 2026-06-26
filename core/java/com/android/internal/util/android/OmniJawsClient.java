/*
* Copyright (C) 2021 The OmniROM Project
* Copyright (C) 2025 AxionOS Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.internal.util.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OmniJawsClient {

    private static final String TAG = "OmniJawsClient";

    public static final String SERVICE_PACKAGE = "org.omnirom.omnijaws";
    public static final String READ_WEATHER_PERMISSION = SERVICE_PACKAGE + ".READ_WEATHER";
    public static final Uri WEATHER_URI =
            Uri.parse("content://org.omnirom.omnijaws.provider/weather");
    public static final Uri SETTINGS_URI =
            Uri.parse("content://org.omnirom.omnijaws.provider/settings");
    public static final Uri HOURLY_URI =
            Uri.parse("content://org.omnirom.omnijaws.provider/hourly");

    private static final String ICON_PACKAGE_DEFAULT = "org.omnirom.omnijaws";
    private static final String ICON_PREFIX_DEFAULT = "google_new";
    private static final String ICON_PREFIX_OUTLINE = "outline";

    public static final String[] WEATHER_PROJECTION = {
            "city", "wind_speed", "wind_direction", "condition_code", "temperature",
            "humidity", "condition", "forecast_low", "forecast_high", "forecast_condition",
            "forecast_condition_code", "time_stamp", "forecast_date", "pin_wheel",
            "feels_like", "pressure", "uvi", "visibility", "dew_point", "sunrise", "sunset"
    };

    public static final String[] SETTINGS_PROJECTION = {
            "enabled", "units", "provider", "setup", "icon_pack"
    };

    public static final String[] HOURLY_PROJECTION = {
            "hourly_temperature", "hourly_condition_code", "hourly_condition", "hourly_timestamp",
            "hourly_humidity", "hourly_wind_speed"
    };

    private static final String KEY_CITY = "city";
    private static final String KEY_WIND_SPEED = "wind_speed";
    private static final String KEY_WIND_DIRECTION = "wind_direction";
    private static final String KEY_CONDITION_CODE = "condition_code";
    private static final String KEY_TEMP = "temperature";
    private static final String KEY_HUMIDITY = "humidity";
    private static final String KEY_CONDITION = "condition";
    private static final String KEY_TIME_STAMP = "time_stamp";
    private static final String KEY_FORECASTS = "forecasts";
    private static final String KEY_HOURLY_FORECASTS = "hourly_forecasts";
    private static final String KEY_TEMP_UNITS = "temp_units";
    private static final String KEY_WIND_UNITS = "wind_units";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_PIN_WHEEL = "pin_wheel";
    private static final String KEY_ICON_PACK = "icon_pack";
    private static final String KEY_FEELS_LIKE = "feels_like";
    private static final String KEY_PRESSURE = "pressure";
    private static final String KEY_UVI = "uvi";
    private static final String KEY_VISIBILITY = "visibility";
    private static final String KEY_DEW_POINT = "dew_point";
    private static final String KEY_SUNRISE = "sunrise";
    private static final String KEY_SUNSET = "sunset";
    private static final String KEY_FORECAST_LOW = "forecast_low";
    private static final String KEY_FORECAST_HIGH = "forecast_high";
    private static final String KEY_FORECAST_DATE = "forecast_date";
    private static final String KEY_HOURLY_TIMESTAMP = "hourly_timestamp";

    private static final DecimalFormat sNoDigitsFormat = new DecimalFormat("0");

    private static OmniJawsClient sInstance;

    private volatile WeatherInfo mCachedInfo;
    private Resources mRes;
    private String mPackageName;
    private String mIconPrefix;
    private String mSettingIconPackage;

    private OmniJawsClient() {}

    public static synchronized OmniJawsClient get() {
        if (sInstance == null) {
            sInstance = new OmniJawsClient();
        }
        return sInstance;
    }

    public WeatherInfo getWeatherInfo() {
        try {
            WeatherInfo info = weatherInfoFromBundle(ActivityManager.getService()
                    .getOmniJawsWeatherInfo(UserHandle.myUserId()));
            mCachedInfo = info;
            return info;
        } catch (RemoteException e) {
            Log.e(TAG, "getWeatherInfo", e);
            return mCachedInfo;
        } catch (RuntimeException e) {
            Log.e(TAG, "getWeatherInfo", e);
            mCachedInfo = null;
            return null;
        }
    }

    public void queryWeather(Context context) {
        if (context == null) {
            mCachedInfo = null;
            return;
        }
        try {
            mCachedInfo = weatherInfoFromBundle(ActivityManager.getService()
                    .queryOmniJawsWeather(context.getUserId()));
            updateSettings(context);
        } catch (RemoteException e) {
            Log.e(TAG, "queryWeather", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "queryWeather", e);
            mCachedInfo = null;
        }
    }

    private void updateSettings(Context context) {
        String iconPack = mCachedInfo != null ? mCachedInfo.iconPack : null;
        if (TextUtils.isEmpty(iconPack)) {
            loadDefaultIconsPackage(context);
        } else if (!iconPack.equals(mSettingIconPackage)) {
            mSettingIconPackage = iconPack;
            loadCustomIconPackage(context);
        }
    }

    private void loadDefaultIconsPackage(Context context) {
        mPackageName = ICON_PACKAGE_DEFAULT;
        mIconPrefix = ICON_PREFIX_DEFAULT;
        mSettingIconPackage = mPackageName + "." + mIconPrefix;
        try {
            mRes = context.getPackageManager().getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            Log.w(TAG, "No default icon package found");
            mRes = null;
        }
    }

    private void loadCustomIconPackage(Context context) {
        int idx = mSettingIconPackage.lastIndexOf(".");
        if (idx == -1) {
            loadDefaultIconsPackage(context);
            return;
        }
        mPackageName = mSettingIconPackage.substring(0, idx);
        mIconPrefix = mSettingIconPackage.substring(idx + 1);
        try {
            mRes = context.getPackageManager().getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            Log.w(TAG, "Icon pack loading failed, fallback to default");
            loadDefaultIconsPackage(context);
        }
    }

    public boolean isOmniJawsEnabled(Context context) {
        if (context == null) return false;
        try {
            return ActivityManager.getService().isOmniJawsEnabled(context.getUserId());
        } catch (RemoteException e) {
            Log.e(TAG, "isOmniJawsEnabled", e);
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "isOmniJawsEnabled", e);
            return false;
        }
    }

    public static Bundle weatherInfoToBundle(WeatherInfo info) {
        if (info == null) return null;
        Bundle bundle = new Bundle();
        bundle.putString(KEY_CITY, info.city);
        bundle.putString(KEY_WIND_SPEED, info.windSpeed);
        bundle.putString(KEY_WIND_DIRECTION, info.windDirection);
        bundle.putInt(KEY_CONDITION_CODE, info.conditionCode);
        bundle.putString(KEY_TEMP, info.temp);
        bundle.putString(KEY_HUMIDITY, info.humidity);
        bundle.putString(KEY_CONDITION, info.condition);
        bundle.putLong(KEY_TIME_STAMP, info.timeStamp != null ? info.timeStamp : 0L);
        bundle.putParcelableArrayList(KEY_FORECASTS, dayForecastsToBundles(info.forecasts));
        bundle.putParcelableArrayList(
                KEY_HOURLY_FORECASTS, hourlyForecastsToBundles(info.hourlyForecasts));
        bundle.putString(KEY_TEMP_UNITS, info.tempUnits);
        bundle.putString(KEY_WIND_UNITS, info.windUnits);
        bundle.putString(KEY_PROVIDER, info.provider);
        bundle.putString(KEY_PIN_WHEEL, info.pinWheel);
        bundle.putString(KEY_ICON_PACK, info.iconPack);
        bundle.putFloat(KEY_FEELS_LIKE, info.feelsLike);
        bundle.putFloat(KEY_PRESSURE, info.pressure);
        bundle.putFloat(KEY_UVI, info.uvi);
        bundle.putFloat(KEY_VISIBILITY, info.visibility);
        bundle.putFloat(KEY_DEW_POINT, info.dewPoint);
        bundle.putLong(KEY_SUNRISE, info.sunrise);
        bundle.putLong(KEY_SUNSET, info.sunset);
        return bundle;
    }

    public static WeatherInfo weatherInfoFromBundle(Bundle bundle) {
        if (bundle == null) return null;
        WeatherInfo info = new WeatherInfo();
        info.city = bundle.getString(KEY_CITY);
        info.windSpeed = bundle.getString(KEY_WIND_SPEED);
        info.windDirection = bundle.getString(KEY_WIND_DIRECTION);
        info.conditionCode = bundle.getInt(KEY_CONDITION_CODE);
        info.temp = bundle.getString(KEY_TEMP);
        info.humidity = bundle.getString(KEY_HUMIDITY);
        info.condition = bundle.getString(KEY_CONDITION);
        info.timeStamp = bundle.getLong(KEY_TIME_STAMP);
        info.forecasts = dayForecastsFromBundles(
                bundle.getParcelableArrayList(KEY_FORECASTS, Bundle.class));
        info.hourlyForecasts = hourlyForecastsFromBundles(
                bundle.getParcelableArrayList(KEY_HOURLY_FORECASTS, Bundle.class));
        info.tempUnits = bundle.getString(KEY_TEMP_UNITS);
        info.windUnits = bundle.getString(KEY_WIND_UNITS);
        info.provider = bundle.getString(KEY_PROVIDER);
        info.pinWheel = bundle.getString(KEY_PIN_WHEEL);
        info.iconPack = bundle.getString(KEY_ICON_PACK);
        info.feelsLike = bundle.getFloat(KEY_FEELS_LIKE, Float.NaN);
        info.pressure = bundle.getFloat(KEY_PRESSURE, Float.NaN);
        info.uvi = bundle.getFloat(KEY_UVI, Float.NaN);
        info.visibility = bundle.getFloat(KEY_VISIBILITY, Float.NaN);
        info.dewPoint = bundle.getFloat(KEY_DEW_POINT, Float.NaN);
        info.sunrise = bundle.getLong(KEY_SUNRISE);
        info.sunset = bundle.getLong(KEY_SUNSET);
        return info;
    }

    private static ArrayList<Bundle> dayForecastsToBundles(List<DayForecast> forecasts) {
        ArrayList<Bundle> bundles = new ArrayList<>();
        if (forecasts == null) return bundles;
        for (DayForecast forecast : forecasts) {
            bundles.add(dayForecastToBundle(forecast));
        }
        return bundles;
    }

    private static ArrayList<DayForecast> dayForecastsFromBundles(ArrayList<Bundle> bundles) {
        ArrayList<DayForecast> forecasts = new ArrayList<>();
        if (bundles == null) return forecasts;
        for (Bundle bundle : bundles) {
            DayForecast forecast = dayForecastFromBundle(bundle);
            if (forecast != null) {
                forecasts.add(forecast);
            }
        }
        return forecasts;
    }

    private static Bundle dayForecastToBundle(DayForecast forecast) {
        Bundle bundle = new Bundle();
        if (forecast == null) return bundle;
        bundle.putString(KEY_FORECAST_LOW, forecast.low);
        bundle.putString(KEY_FORECAST_HIGH, forecast.high);
        bundle.putInt(KEY_CONDITION_CODE, forecast.conditionCode);
        bundle.putString(KEY_CONDITION, forecast.condition);
        bundle.putString(KEY_FORECAST_DATE, forecast.date);
        return bundle;
    }

    private static DayForecast dayForecastFromBundle(Bundle bundle) {
        if (bundle == null) return null;
        DayForecast forecast = new DayForecast();
        forecast.low = bundle.getString(KEY_FORECAST_LOW);
        forecast.high = bundle.getString(KEY_FORECAST_HIGH);
        forecast.conditionCode = bundle.getInt(KEY_CONDITION_CODE);
        forecast.condition = bundle.getString(KEY_CONDITION);
        forecast.date = bundle.getString(KEY_FORECAST_DATE);
        return forecast;
    }

    private static ArrayList<Bundle> hourlyForecastsToBundles(List<HourlyForecast> forecasts) {
        ArrayList<Bundle> bundles = new ArrayList<>();
        if (forecasts == null) return bundles;
        for (HourlyForecast forecast : forecasts) {
            bundles.add(hourlyForecastToBundle(forecast));
        }
        return bundles;
    }

    private static ArrayList<HourlyForecast> hourlyForecastsFromBundles(ArrayList<Bundle> bundles) {
        ArrayList<HourlyForecast> forecasts = new ArrayList<>();
        if (bundles == null) return forecasts;
        for (Bundle bundle : bundles) {
            HourlyForecast forecast = hourlyForecastFromBundle(bundle);
            if (forecast != null) {
                forecasts.add(forecast);
            }
        }
        return forecasts;
    }

    private static Bundle hourlyForecastToBundle(HourlyForecast forecast) {
        Bundle bundle = new Bundle();
        if (forecast == null) return bundle;
        bundle.putFloat(KEY_TEMP, forecast.temperature);
        bundle.putInt(KEY_CONDITION_CODE, forecast.conditionCode);
        bundle.putString(KEY_CONDITION, forecast.condition);
        bundle.putLong(KEY_HOURLY_TIMESTAMP, forecast.timestamp);
        bundle.putFloat(KEY_HUMIDITY, forecast.humidity);
        bundle.putFloat(KEY_WIND_SPEED, forecast.windSpeed);
        return bundle;
    }

    private static HourlyForecast hourlyForecastFromBundle(Bundle bundle) {
        if (bundle == null) return null;
        return new HourlyForecast(bundle.getFloat(KEY_TEMP, Float.NaN),
                bundle.getInt(KEY_CONDITION_CODE), bundle.getString(KEY_CONDITION),
                bundle.getLong(KEY_HOURLY_TIMESTAMP), bundle.getFloat(KEY_HUMIDITY, Float.NaN),
                bundle.getFloat(KEY_WIND_SPEED, Float.NaN));
    }

    public static String getFormattedValue(float value) {
        if (Float.isNaN(value)) return "-";
        String result = sNoDigitsFormat.format(value);
        return result.equals("-0") ? "0" : result;
    }

    public static String getTemperatureUnit(boolean metric) {
        return metric ? "\u00b0C" : "\u00b0F";
    }

    public static String getWindUnit(boolean metric) {
        return metric ? "km/h" : "mph";
    }

    public boolean isOutlineIconPackage() {
        return ICON_PREFIX_OUTLINE.equals(mIconPrefix);
    }

    public Drawable getWeatherConditionImage(Context context, int conditionCode) {
        if (mRes == null) {
            loadDefaultIconsPackage(context);
        }
        try {
            int resId = mRes.getIdentifier(
                    mIconPrefix + "_" + conditionCode, "drawable", mPackageName);
            Drawable d = mRes.getDrawable(resId, null);
            return d != null ? d : getDefaultConditionImage(context);
        } catch (Exception e) {
            Log.e(TAG, "getWeatherConditionImage", e);
            return getDefaultConditionImage(context);
        }
    }

    private Drawable getDefaultConditionImage(Context context) {
        try {
            Resources res = context.getPackageManager()
                    .getResourcesForApplication(ICON_PACKAGE_DEFAULT);
            int resId = res.getIdentifier(
                    ICON_PREFIX_DEFAULT + "_na", "drawable", ICON_PACKAGE_DEFAULT);
            Drawable d = res.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.RED);
        } catch (Exception e) {
            return new ColorDrawable(Color.RED);
        }
    }

    public Drawable getResOmni(Context context, String iconOmni) {
        if (mRes == null) loadDefaultIconsPackage(context);
        try {
            int resId = mRes.getIdentifier(iconOmni, "drawable", mPackageName);
            Drawable d = mRes.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.RED);
        } catch (Exception e) {
            Log.e(TAG, "getResOmni", e);
            return new ColorDrawable(Color.RED);
        }
    }

    public static class WeatherInfo {
        public String city;
        public String windSpeed;
        public String windDirection;
        public int conditionCode;
        public String temp;
        public String humidity;
        public String condition;
        public Long timeStamp;
        public List<DayForecast> forecasts;
        public List<HourlyForecast> hourlyForecasts;
        public String tempUnits;
        public String windUnits;
        public String provider;
        public String pinWheel;
        public String iconPack;
        public float feelsLike = Float.NaN;
        public float pressure = Float.NaN;
        public float uvi = Float.NaN;
        public float visibility = Float.NaN;
        public float dewPoint = Float.NaN;
        public long sunrise;
        public long sunset;

        public String getLastUpdateTime() {
            long timestamp = timeStamp != null ? timeStamp : 0L;
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestamp));
        }

        @Override
        public String toString() {
            return city + " @ " + new Date(timeStamp != null ? timeStamp : 0L) + " | "
                    + condition + " | " + temp;
        }
    }

    public static class DayForecast {
        public String low;
        public String high;
        public int conditionCode;
        public String condition;
        public String date;

        @Override
        public String toString() {
            return "[" + date + " - " + low + "/" + high + " - " + condition + "]";
        }
    }

    public static class HourlyForecast {
        public float temperature;
        public int conditionCode;
        public String condition;
        public long timestamp;
        public float humidity;
        public float windSpeed;

        public HourlyForecast() {}

        public HourlyForecast(float temperature, int conditionCode, String condition,
                long timestamp, float humidity, float windSpeed) {
            this.temperature = temperature;
            this.conditionCode = conditionCode;
            this.condition = condition;
            this.timestamp = timestamp;
            this.humidity = humidity;
            this.windSpeed = windSpeed;
        }

        @Override
        public String toString() {
            return "[" + new Date(timestamp) + " - " + temperature + " - " + condition + "]";
        }
    }
}
