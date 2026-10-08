package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.crawler.SpiderDebug;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * fork 定制：Android 11+ 且系统没提供「所有文件访问」设置页时，用 SAF（系统目录选择器）授权写共享存储。
 *
 * 背景：写 /sdcard/TV、/sdcard/TVBox、/sdcard/TVData 在 Android 11+ 通常要 MANAGE_EXTERNAL_STORAGE，
 * 但部分电视 ROM 直接把「所有文件访问」设置页裁掉了，用户连授权的入口都没有，同步这些目录必然被跳过。
 * 这里让接收端在同步面板里用系统目录选择器（ACTION_OPEN_DOCUMENT_TREE）授权一次目录，
 * 授权持久化后走树 URI + DocumentsContract 写入，完全不依赖 MANAGE_EXTERNAL_STORAGE。
 *
 * 授权粒度：可以只授权 TV / TVBox / TVData 三个目录，也可以直接授权内部存储根目录（一次覆盖全部）。
 */
public class SharedDirAccess {

    private static final String PREF = "shared_dir_access";
    private static final int BUFFER_SIZE = 128 * 1024;

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 记录一次目录授权；path 为相对共享存储根的路径，授权内部存储根目录时为空串。 */
    public static void save(Uri uri) {
        String path = relativePath(uri);
        if (path == null || uri == null) return;
        try {
            App.get().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            SpiderDebug.log("sync-saf", e);
        }
        prefs().edit().putString(path, uri.toString()).apply();
        SpiderDebug.log("sync-saf", "granted path=%s uri=%s", path, uri);
    }

    /**
     * 从树 URI 反推「相对共享存储根」的路径。
     * 非内部存储（SD 卡 / U 盘 / 其它 DocumentsProvider）返回 null，避免把文件写到别处。
     */
    public static String relativePath(Uri uri) {
        if (uri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int index = docId.indexOf(':');
            if (index < 0) return null;
            String volume = docId.substring(0, index);
            if (!"primary".equalsIgnoreCase(volume) && !"home".equalsIgnoreCase(volume)) return null;
            return trim(docId.substring(index + 1));
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear() {
        prefs().edit().clear().apply();
    }

    public static boolean hasAny() {
        return !prefs().getAll().isEmpty();
    }

    /** 找到能覆盖该相对路径的已授权目录（最长前缀优先）。 */
    public static Uri find(String path) {
        String target = SyncFiles.normalize(path);
        String best = null;
        Object value = null;
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            if (!within(entry.getKey(), target)) continue;
            if (best == null || entry.getKey().length() > best.length()) {
                best = entry.getKey();
                value = entry.getValue();
            }
        }
        if (value == null) return null;
        try {
            return Uri.parse((String) value);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean covers(String path) {
        return find(path) != null;
    }

    public static boolean covers(List<String> paths) {
        if (paths == null || paths.isEmpty()) return false;
        for (String path : paths) if (!covers(path)) return false;
        return true;
    }

    /** 已授权目录（UI 展示用）。 */
    public static String describe() {
        List<String> keys = new ArrayList<>(prefs().getAll().keySet());
        if (keys.isEmpty()) return "";
        Collections.sort(keys);
        List<String> result = new ArrayList<>();
        for (String key : keys) result.add(TextUtils.isEmpty(key) ? ResUtil.getString(R.string.sync_shared_auth_root) : key);
        return TextUtils.join(" / ", result);
    }

    /**
     * 按已授权的目录把归档写进共享存储。返回成功写入的文件数。
     * 未授权目录记入 stats.skipped，写入失败记入 stats.failed —— 单个文件失败不影响其它文件。
     */
    public static int restore(File archive, Stats stats) throws IOException {
        if (archive == null || !archive.isFile() || archive.length() <= 0) return 0;
        ContentResolver resolver = App.get().getContentResolver();
        byte[] buffer = new byte[BUFFER_SIZE];
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String path = SyncFiles.normalize(entry.getName());
                if (path.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                try {
                    Uri tree = find(path);
                    if (tree == null) throw new MissingAccessException(path);
                    if (entry.isDirectory()) {
                        ensureDirectory(resolver, tree, path);
                    } else {
                        write(resolver, ensureFile(resolver, tree, path), zis, buffer);
                        count++;
                    }
                } catch (MissingAccessException e) {
                    stats.skipped.add(top(e.path));
                } catch (Exception e) {
                    SpiderDebug.log("sync-saf", e);
                    stats.failed++;
                }
                zis.closeEntry();
            }
        }
        stats.restored = count;
        SpiderDebug.log("sync-saf", "restore count=%d skipped=%s failed=%d file=%s", count, stats.skipped, stats.failed, archive.getAbsolutePath());
        return count;
    }

    private static Uri ensureDirectory(ContentResolver resolver, Uri tree, String path) throws Exception {
        String root = relativePath(tree);
        if (root == null) throw new IOException("invalid tree uri");
        String target = SyncFiles.normalize(path);
        if (!within(root, target)) throw new IOException("outside tree: " + target);
        if (target.equals(root)) return treeDocument(resolver, tree);
        Uri parent = ensureDirectory(resolver, tree, parentOf(target));
        Uri existing = findChild(resolver, tree, parent, leafOf(target));
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, leafOf(target));
        if (created == null) throw new IOException("create directory failed: " + target);
        return created;
    }

    private static Uri ensureFile(ContentResolver resolver, Uri tree, String path) throws Exception {
        String target = SyncFiles.normalize(path);
        Uri parent = ensureDirectory(resolver, tree, parentOf(target));
        Uri existing = findChild(resolver, tree, parent, leafOf(target));
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(resolver, parent, mimeOf(leafOf(target)), leafOf(target));
        if (created == null) throw new IOException("create file failed: " + target);
        return created;
    }

    private static Uri treeDocument(ContentResolver resolver, Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private static Uri findChild(ContentResolver resolver, Uri tree, Uri parent, String name) {
        try {
            String parentId = DocumentsContract.getDocumentId(parent);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            String[] columns = new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    if (name.equals(cursor.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("sync-saf", e);
        }
        return null;
    }

    private static void write(ContentResolver resolver, Uri file, ZipInputStream zis, byte[] buffer) throws IOException {
        OutputStream output = null;
        try {
            output = resolver.openOutputStream(file, "wt");
            if (output == null) throw new IOException("open output failed: " + file);
            BufferedOutputStream bos = new BufferedOutputStream(output, BUFFER_SIZE);
            int read;
            while ((read = zis.read(buffer)) != -1) bos.write(buffer, 0, read);
            bos.flush();
        } finally {
            if (output != null) try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String mimeOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") || lower.endsWith(".ini") || lower.endsWith(".conf")) return "text/plain";
        return "application/octet-stream";
    }

    private static boolean within(String parent, String child) {
        String p = trim(parent);
        String c = trim(child);
        return p.isEmpty() || p.equals(c) || c.startsWith(p + "/");
    }

    private static String parentOf(String path) {
        String value = trim(path);
        int index = value.lastIndexOf('/');
        return index < 0 ? "" : value.substring(0, index);
    }

    private static String leafOf(String path) {
        String value = trim(path);
        int index = value.lastIndexOf('/');
        return index < 0 ? value : value.substring(index + 1);
    }

    private static String top(String path) {
        String value = trim(path);
        int index = value.indexOf('/');
        return index < 0 ? value : value.substring(0, index);
    }

    private static String trim(String path) {
        if (path == null) return "";
        String value = path.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public static class Stats {

        private int restored;
        private int failed;
        private final Set<String> skipped = new LinkedHashSet<>();

        public int getRestored() {
            return restored;
        }

        public int getFailed() {
            return failed;
        }

        public Set<String> getSkipped() {
            return skipped;
        }
    }

    private static class MissingAccessException extends Exception {

        private final String path;

        private MissingAccessException(String path) {
            this.path = path;
        }
    }
}
