package com.fongmi.android.tv.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.db.dao.ConfigDao;
import com.fongmi.android.tv.db.dao.DeviceDao;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.db.dao.KeepDao;
import com.fongmi.android.tv.db.dao.LiveDao;
import com.fongmi.android.tv.db.dao.PlaybackDeleteTombstoneDao;
import com.fongmi.android.tv.db.dao.SiteDao;
import com.fongmi.android.tv.db.dao.TrackDao;
import com.fongmi.android.tv.utils.AppBackup;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Database(entities = {Keep.class, Site.class, Live.class, Track.class, Config.class, Device.class, History.class, PlaybackDeleteTombstone.class}, version = AppDatabase.VERSION)
public abstract class AppDatabase extends RoomDatabase {

    public static final int VERSION = 37;
    public static final String NAME = "tv";
    public static final String SYMBOL = "@@@";

    private static volatile AppDatabase instance;

    public static synchronized AppDatabase get() {
        if (instance == null) instance = create(App.get());
        return instance;
    }

    public static void backup() {
        backup(new com.fongmi.android.tv.impl.Callback());
    }

    public static void backup(com.fongmi.android.tv.impl.Callback callback) {
        backup(callback, null);
    }

    /**
     * 每周自动备份：距上次自动备份超过 7 天则备份一次，并清理旧的备份文件（保留最近 4 份）。
     * 在后台线程执行，不阻塞启动。
     */
    public static void backupIfDue() {
        Task.execute(() -> {
            try {
                long last = Prefers.getLong("auto_backup_at", 0);
                long now = System.currentTimeMillis();
                if (now - last < WEEK_MS) return;
                File file = new File(Path.tv(), AppBackup.fileName());
                AppBackup.create(file, null);
                Prefers.put("auto_backup_at", now);
                SpiderDebug.log("backup", "weekly auto backup done file=%s size=%d", file.getName(), file.length());
                cleanOld(4);
            } catch (Throwable e) {
                SpiderDebug.log("backup", "weekly auto backup failed error=%s", e.getMessage());
            }
        });
    }

    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    public static void backup(com.fongmi.android.tv.impl.Callback callback, AppBackup.Progress progress) {
        Task.execute(() -> {
            File file = new File(Path.tv(), AppBackup.fileName());
            try {
                AppBackup.CreateResult result = AppBackup.create(file, progress);
                App.post(() -> {
                    callback.success();
                    if (result.hasWarning()) Notify.show(result.warning);
                });
                cleanOld();
            } catch (Exception e) {
                SpiderDebug.log("backup", "local create failed error=%s", e.getMessage());
                App.post(callback::error);
            }
        });
    }

    public static void restore(File file, com.fongmi.android.tv.impl.Callback callback) {
        restore(file, callback, null);
    }

    public static void restore(File file, com.fongmi.android.tv.impl.Callback callback, AppBackup.Progress progress) {
        Task.execute(() -> {
            try {
                AppBackup.RestoreResult result = AppBackup.restore(file, progress);
                App.post(() -> {
                    callback.success();
                    if (result.hasWarning()) Notify.show(result.warning);
                });
            } catch (Exception e) {
                SpiderDebug.log("backup", "local restore failed file=%s error=%s", file == null ? "" : file.getAbsolutePath(), e.getMessage());
                App.post(callback::error);
            }
        });
    }

    private static void cleanOld() {
        cleanOld(7);
    }

    private static void cleanOld(int keep) {
        List<File> items = new ArrayList<>();
        File[] files = Path.tv().listFiles();
        if (files == null) files = new File[0];
        for (File file : files) if (AppBackup.isBackup(file)) items.add(file);
        if (!items.isEmpty()) items.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        if (items.size() > keep) for (int i = keep; i < items.size(); i++) Path.clear(items.get(i));
    }

    private static AppDatabase create(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, NAME)
                .addMigrations(Migrations.MIGRATION_30_31)
                .addMigrations(Migrations.MIGRATION_31_32)
                .addMigrations(Migrations.MIGRATION_32_33)
                .addMigrations(Migrations.MIGRATION_33_34)
                .addMigrations(Migrations.MIGRATION_34_35)
                .addMigrations(Migrations.MIGRATION_35_36)
                .addMigrations(Migrations.MIGRATION_36_37)
                .fallbackToDestructiveMigration(true)
                .allowMainThreadQueries().build();
    }

    public abstract KeepDao getKeepDao();

    public abstract SiteDao getSiteDao();

    public abstract LiveDao getLiveDao();

    public abstract TrackDao getTrackDao();

    public abstract ConfigDao getConfigDao();

    public abstract DeviceDao getDeviceDao();

    public abstract HistoryDao getHistoryDao();

    public abstract PlaybackDeleteTombstoneDao getPlaybackDeleteTombstoneDao();
}
