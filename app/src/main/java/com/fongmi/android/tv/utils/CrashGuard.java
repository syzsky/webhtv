package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Environment;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 最后一道兜底：任何未捕获异常先落盘记录，再决定是否放行。
 * 主线程异常不再交给系统默认 handler，避免"源失效/蜘蛛异常"直接把界面闪退掉。
 */
public final class CrashGuard {

    private static final String TAG = "crash-guard";
    private static final String FILE_NAME = "webhtv_crash.log";
    private static final long MAX_BYTES = 256L * 1024L;

    private static volatile boolean installed;
    private static volatile Context context;

    private CrashGuard() {
    }

    public static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        context = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String detail = dump(throwable);
            save(detail);
            try {
                SpiderDebug.log(TAG, "uncaught thread=%s\n%s", thread == null ? "unknown" : thread.getName(), detail);
            } catch (Throwable ignored) {
            }
            if (thread != null && thread == Looper.getMainLooper().getThread()) return;
            if (previous != null) {
                try {
                    previous.uncaughtException(thread, throwable);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static String dump(Throwable throwable) {
        if (throwable == null) return "unknown";
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        throwable.printStackTrace(printer);
        printer.flush();
        return writer.toString();
    }

    public static String read() {
        File file = primaryFile();
        if (file == null || !file.isFile()) return "";
        try {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_BYTES)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            int read = in.read(data);
            in.close();
            if (read <= 0) return "";
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private static void save(String text) {
        if (text == null || text.isEmpty()) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String body = "\n===== " + stamp + " =====\n" + text;
        append(primaryFile(), body);
        append(publicFile(), body);
    }

    private static void append(File file, String body) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (file.isFile() && file.length() > MAX_BYTES) file.delete();
            FileOutputStream out = new FileOutputStream(file, true);
            OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(body);
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private static File primaryFile() {
        Context ctx = context;
        if (ctx == null) return null;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            return dir == null ? null : new File(dir, FILE_NAME);
        } catch (Throwable e) {
            return null;
        }
    }

    private static File publicFile() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            return dir == null ? null : new File(dir, FILE_NAME);
        } catch (Throwable e) {
            return null;
        }
    }
}
