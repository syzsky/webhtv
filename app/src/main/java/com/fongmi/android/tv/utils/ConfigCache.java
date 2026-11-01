package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

/**
 * 配置源快照缓存。
 *
 * 订阅源（VOD / LIVE / WALL）拉取成功后把原始文本落盘，源地址失效、DNS 被污染、
 * 返回非 JSON 或被运营商拦截时读取上次快照继续启动，避免首页/直播在初始化阶段
 * 直接抛异常导致进程崩溃。
 */
public final class ConfigCache {

    private static final String NAME = "config_snapshot";
    private static final int MAX_LENGTH = 4 * 1024 * 1024;

    private ConfigCache() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static String key(String url) {
        return "snapshot_" + Integer.toHexString(TextUtils.isEmpty(url) ? 0 : url.hashCode());
    }

    public static void save(String url, String content) {
        try {
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(content) || content.length() > MAX_LENGTH) return;
            prefs().edit().putString(key(url), content).apply();
        } catch (Throwable e) {
            SpiderDebug.log("config-cache", "save failed url=%s msg=%s", url, e.getMessage());
        }
    }

    public static String load(String url) {
        try {
            if (TextUtils.isEmpty(url)) return "";
            String content = prefs().getString(key(url), "");
            return TextUtils.isEmpty(content) ? "" : content;
        } catch (Throwable e) {
            SpiderDebug.log("config-cache", "load failed url=%s msg=%s", url, e.getMessage());
            return "";
        }
    }

    public static void clear(String url) {
        try {
            if (TextUtils.isEmpty(url)) return;
            prefs().edit().remove(key(url)).apply();
        } catch (Throwable ignored) {
        }
    }
}
