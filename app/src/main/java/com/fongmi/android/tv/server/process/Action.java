package com.fongmi.android.tv.server.process;

import android.os.Build;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.SyncOptions;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.LoginStateSync;
import com.fongmi.android.tv.utils.MpvConfigSync;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ProgressRequestBody;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SyncFiles;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public class Action implements Process {

    private static final MediaType ZIP = MediaType.parse("application/zip");
    private static final String APK_PART = "apk";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/action");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String param = params.get("do");
        SpiderDebug.log("action", "do=%s params=%s", param, params);
        return TextUtils.isEmpty(param) ? Nano.ok() : doJob(param, params, files);
    }

    private Response doJob(String param, Map<String, String> params, Map<String, String> files) {
        return switch (param) {
            case "file" -> {
                onFile(params);
                yield Nano.ok();
            }
            case "push" -> {
                onPush(params);
                yield Nano.ok();
            }
            case "cast" -> {
                onCast(params);
                yield Nano.ok();
            }
            case "sync" -> onSync(params, files);
            case "apk" -> onApk(params, files);
            case "apk_url" -> onApkUrl(params);
            case "search" -> {
                onSearch(params);
                yield Nano.ok();
            }
            case "setting" -> {
                onSetting(params);
                yield Nano.ok();
            }
            case "refresh" -> {
                onRefresh(params);
                yield Nano.ok();
            }
            case "control" -> {
                onControl(params);
                yield Nano.ok();
            }
            case "danmaku" -> {
                onDanmaku(params);
                yield Nano.ok();
            }
            default -> Nano.ok();
        };
    }

    private void onFile(Map<String, String> params) {
        String path = params.get("path");
        if (TextUtils.isEmpty(path)) return;
        if (path.endsWith(".apk")) FileUtil.openFile(Path.local(path));
        else if (path.endsWith(".srt") || path.endsWith(".ssa") || path.endsWith(".ass")) RefreshEvent.subtitle(path);
        else ServerEvent.setting(path);
    }

    private void onPush(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return;
        ServerEvent.push(url);
    }

    private void onSearch(Map<String, String> params) {
        String word = params.get("word");
        if (TextUtils.isEmpty(word)) return;
        ServerEvent.search(word);
    }

    private void onSetting(Map<String, String> params) {
        String text = params.get("text");
        String name = params.get("name");
        if (TextUtils.isEmpty(text)) return;
        ServerEvent.setting(text, name);
    }

    private void onRefresh(Map<String, String> params) {
        String type = params.get("type");
        String path = params.get("path");
        String json = params.get("json");
        if (TextUtils.isEmpty(type)) return;
        switch (type) {
            case "home" -> RefreshEvent.home();
            case "live" -> RefreshEvent.live();
            case "detail" -> RefreshEvent.detail();
            case "player" -> RefreshEvent.player();
            case "category" -> RefreshEvent.category();
            case "danmaku" -> RefreshEvent.danmaku(path);
            case "subtitle" -> RefreshEvent.subtitle(path);
            case "vod" -> RefreshEvent.vod(Vod.objectFrom(json));
        }
    }

    private void onControl(Map<String, String> params) {
        String type = params.get("type");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(type)) return;
        switch (type) {
            case "play" -> App.post(() -> service.player().play());
            case "pause" -> App.post(() -> service.player().pause());
            case "stop" -> App.post(service::dispatchStop);
            case "prev" -> App.post(service::dispatchPrev);
            case "next" -> App.post(service::dispatchNext);
            case "repeat" -> App.post(service::dispatchRepeat);
            case "replay" -> App.post(service::dispatchReplay);
        }
    }

    private void onDanmaku(Map<String, String> params) {
        String text = params.get("text");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(text)) return;
        App.post(() -> service.player().sendDanmaku(text));
    }

    private void onCast(Map<String, String> params) {
        Config config = Config.objectFrom(params.get("config"));
        Device device = Device.objectFrom(params.get("device"));
        History history = History.objectFrom(params.get("history"));
        CastEvent.post(Config.find(config), device, history);
    }

    private Response onSync(Map<String, String> params, Map<String, String> files) {
        try {
            String type = params.get("type");
            boolean force = Objects.equals(params.get("force"), "true");
            String mode = Objects.requireNonNullElse(params.get("mode"), "0");
            boolean success = true;
            if (params.get("device") != null && (mode.equals("0") || mode.equals("2"))) {
                Device device = Device.objectFrom(params.get("device"));
                if ("history".equals(type)) success = sendHistory(device, params);
                else if ("keep".equals(type)) success = sendKeep(device);
                else if ("backup".equals(type)) success = sendBackup(device, params);
            }
            if (mode.equals("0") || mode.equals("1")) {
                if ("history".equals(type)) syncHistory(params, force);
                else if ("keep".equals(type)) syncKeep(params, force);
                else if ("backup".equals(type)) {
                    // fork 定制：请求体没解析出 options/backup 时直接给出可读原因。
                    // 否则会一路走到 Backup.restore 里 insertOrUpdate(null) 的 NPE，
                    // 而 NPE 的 getMessage() 是 null —— 客户端只会看到一句「同步失败」，查不出所以然。
                    String missing = missingSyncParams(params);
                    if (missing != null) return Nano.error(ResUtil.getString(R.string.sync_failed) + "\n" + missing);
                    String warning = syncBackup(params, files, force);
                    // 共享存储没写成功时用 4xx：这类问题重试结果一样，别让客户端白重传一遍大归档。
                    if (warning != null) return Nano.error(Response.Status.BAD_REQUEST, warning);
                }
            }
            return success ? Nano.ok() : Nano.error(ResUtil.getString(R.string.sync_failed));
        } catch (Exception e) {
            SpiderDebug.log("sync", e);
            return Nano.error(syncError(e));
        }
    }

    private String missingSyncParams(Map<String, String> params) {
        StringBuilder missing = new StringBuilder();
        if (TextUtils.isEmpty(params.get("options"))) missing.append("options ");
        if (TextUtils.isEmpty(params.get("backup"))) missing.append("backup ");
        if (missing.length() == 0) return null;
        return ResUtil.getString(R.string.sync_missing_params, missing.toString().trim());
    }

    /** 把异常类名也带上——有些异常（例如 NPE）getMessage() 是 null，只回传 getMessage() 等于没说。 */
    private String syncError(Throwable e) {
        return ResUtil.getString(R.string.sync_failed) + "\n" + syncErrorDetail(e);
    }

    private String syncErrorDetail(Throwable e) {
        StringBuilder detail = new StringBuilder(e.getClass().getSimpleName());
        if (!TextUtils.isEmpty(e.getMessage())) detail.append(": ").append(e.getMessage());
        for (Throwable cause = e.getCause(); cause != null && cause != e; cause = cause.getCause()) {
            detail.append(" <- ").append(cause.getClass().getSimpleName());
            if (!TextUtils.isEmpty(cause.getMessage())) detail.append(": ").append(cause.getMessage());
        }
        return detail.toString();
    }

    private Response onApk(Map<String, String> params, Map<String, String> files) {
        File target = null;
        try {
            if (files == null || !files.containsKey(APK_PART)) throw new IllegalArgumentException("Missing APK file");
            String name = sanitizeApkName(params.get("name"));
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IllegalArgumentException("Invalid APK name");
            File source = new File(files.get(APK_PART));
            long expectedSize = parseLong(params.get("size"));
            if (!source.isFile() || source.length() <= 0) throw new IllegalArgumentException("Empty APK file");
            if (expectedSize > 0 && source.length() != expectedSize) throw new IllegalArgumentException("APK size mismatch");
            String expectedSha256 = params.get("sha256");
            if (!TextUtils.isEmpty(expectedSha256) && !expectedSha256.equalsIgnoreCase(sha256(source))) throw new IllegalArgumentException("APK checksum mismatch");
            if (App.get().getPackageManager().getPackageArchiveInfo(source.getAbsolutePath(), 0) == null) throw new IllegalArgumentException("Invalid APK package");
            long available = FileUtil.getAvailableStorageSpace(Path.cache());
            if (available > 0 && source.length() + 16L * 1024 * 1024 > available) throw new IllegalStateException("Insufficient storage");
            target = Path.cache("pushed-" + System.currentTimeMillis() + ".apk");
            Path.copy(source, target);
            if (target.length() != source.length()) throw new IllegalStateException("APK copy incomplete");
            File installFile = target;
            Device senderDevice = Device.objectFrom(params.get("device"));
            String sender = senderDevice == null ? "" : senderDevice.getName();
            SpiderDebug.log("apk-push", "received name=%s size=%d sender=%s", name, target.length(), sender);
            App.post(() -> {
                try {
                    FileUtil.openFile(installFile);
                } catch (Exception e) {
                    Path.clear(installFile);
                    Notify.show(e.getMessage());
                }
            });
            Task.schedule(() -> Path.clear(installFile), 30, TimeUnit.MINUTES);
            return Nano.ok("APK received");
        } catch (Exception e) {
            if (target != null) Path.clear(target);
            SpiderDebug.log("apk-push", e);
            return Nano.error(fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    private Response onApkUrl(Map<String, String> params) {
        try {
            Device senderDevice = Device.objectFrom(params.get("device"));
            String sender = senderDevice == null ? "" : senderDevice.getName();
            ApkUrlPush.StartResult result = ApkUrlPush.get().start(params.get("url"), sender);
            if (result == ApkUrlPush.StartResult.BUSY) return Nano.error(Response.Status.CONFLICT, ResUtil.getString(R.string.apk_push_url_busy));
            return Nano.ok("APK URL accepted");
        } catch (IllegalArgumentException e) {
            return Nano.error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            SpiderDebug.log("apk-push-url", e);
            return Nano.error(ResUtil.getString(R.string.apk_push_url_schedule_failed));
        }
    }

    private String sanitizeApkName(String name) {
        if (TextUtils.isEmpty(name)) return "app.apk";
        return name.replace('\\', '_').replace('/', '_').replace('\u0000', '_').trim();
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private boolean post(Device device, String type, FormBody.Builder body) {
        return post(device, type, body.build());
    }

    private boolean post(Device device, String type, RequestBody body) {
        try {
            try (okhttp3.Response response = OkHttp.newCall(OkHttp.client(Constant.TIMEOUT_SYNC_TRANSFER), device.getIp().concat("/action?do=sync&mode=0&type=" + type), body).execute()) {
                if (response.isSuccessful()) return true;
                throw new IllegalStateException(response.message());
            }
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
            return false;
        }
    }

    private boolean sendHistory(Device device, Map<String, String> params) {
        try {
            Config config = Config.find(Config.objectFrom(params.get("config")));
            if (config.getUrl() == null) config = Config.vod();
            FormBody.Builder body = new FormBody.Builder();
            body.add("config", config.toString());
            body.add("targets", App.gson().toJson(History.get(config.getId())));
            return post(device, "history", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
            return false;
        }
    }

    private boolean sendKeep(Device device) {
        try {
            FormBody.Builder body = new FormBody.Builder();
            body.add("targets", App.gson().toJson(Keep.getVod()));
            body.add("configs", App.gson().toJson(Config.findUrls()));
            return post(device, "keep", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
            return false;
        }
    }

    private boolean sendBackup(Device device, Map<String, String> params) {
        try {
            SyncOptions options = SyncOptions.objectFrom(params.get("options"));
            SyncFiles.Archive archive = SyncFiles.hasPaths(options) ? SyncFiles.createArchive(SyncFiles.getPaths(options)) : null;
            MpvConfigSync.Archive mpvArchive = options.isMpvConfig() ? MpvConfigSync.createArchive() : null;
            LoginStateSync.Archive loginArchive = options.isLoginState() ? LoginStateSync.createArchive() : null;
            try {
                return post(device, "backup", getBackupBody(options, archive, mpvArchive, loginArchive));
            } finally {
                if (archive != null) archive.delete();
                if (mpvArchive != null) mpvArchive.delete();
                if (loginArchive != null) loginArchive.delete();
            }
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
            return false;
        }
    }

    private RequestBody getBackupBody(SyncOptions options, SyncFiles.Archive archive, MpvConfigSync.Archive mpvArchive, LoginStateSync.Archive loginArchive) {
        if (archive == null && mpvArchive == null && loginArchive == null) {
            FormBody.Builder body = new FormBody.Builder();
            body.add("options", options.toString());
            body.add("backup", Backup.create(options).toString());
            if (options.isRemoteRelay()) body.add("remoteRelay", RemoteStore.exportRelayConfig());
            return body.build();
        }
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        body.addFormDataPart("options", options.toString());
        body.addFormDataPart("backup", Backup.create(options).toString());
        if (options.isRemoteRelay()) body.addFormDataPart("remoteRelay", RemoteStore.exportRelayConfig());
        if (archive != null) body.addFormDataPart(SyncFiles.PART_NAME, archive.getFile().getName(), new ProgressRequestBody(archive.getFile(), ZIP, null));
        if (mpvArchive != null) body.addFormDataPart(MpvConfigSync.PART_NAME, mpvArchive.getFile().getName(), new ProgressRequestBody(mpvArchive.getFile(), ZIP, null));
        if (loginArchive != null) body.addFormDataPart(LoginStateSync.PART_NAME, loginArchive.getFile().getName(), new ProgressRequestBody(loginArchive.getFile(), ZIP, null));
        return body.build();
    }

    /**
     * 还原备份。返回 null 表示全部成功；返回非 null 表示「应用数据已还原，但共享存储目录被跳过」，
     * 由调用方回给客户端（用 4xx，避免无意义重试）。
     *
     * fork 定制：写 /sdcard 需要存储权限（Android 11+ 是 MANAGE_EXTERNAL_STORAGE）。
     * 没有权限时不再 throw 打断整次同步——收藏 / 历史 / 设置 / 源都在应用私有目录
     * （Room + SharedPreferences），照样能还原；只有共享存储下的 TV / TVBox / TVData 需要写权限。
     * 整次失败会让用户连这些也拿不到，如实告知「哪些被跳过」更有用。
     *
     * 注意反方向 sendBackup 只「读」/sdcard（临时 zip 写 Path.cache() 私有目录），
     * 所以只需要读权限——这正是「手机能拉取、不能推送」的原因。
     */
    private String syncBackup(Map<String, String> params, Map<String, String> files, boolean force) {
        Backup backup = Backup.objectFrom(params.get("backup"));
        SyncOptions options = SyncOptions.objectFrom(params.get("options"));
        boolean fileAccess = Setting.hasFileAccess();
        String warning = null;
        if (SyncFiles.hasPaths(options) && files.containsKey(SyncFiles.PART_NAME)) {
            File archive = new File(files.get(SyncFiles.PART_NAME));
            try {
                if (fileAccess) {
                    SyncFiles.restoreArchive(archive);
                } else {
                    SpiderDebug.log("sync", "skip shared-storage restore: no file access, hasFileManager=%s", Setting.hasFileManager());
                    warning = sharedStorageWarning();
                }
            } catch (Exception e) {
                SpiderDebug.log("sync", e);
                warning = ResUtil.getString(R.string.sync_shared_storage_failed, syncErrorDetail(e));
            } finally {
                Path.clear(archive);
            }
        }
        if (options.isMpvConfig() && files.containsKey(MpvConfigSync.PART_NAME)) {
            File archive = new File(files.get(MpvConfigSync.PART_NAME));
            try {
                MpvConfigSync.restoreArchive(archive);
            } catch (Exception e) {
                SpiderDebug.log("sync", e);
            } finally {
                Path.clear(archive);
            }
        }
        if (options.isLoginState() && files.containsKey(LoginStateSync.PART_NAME)) {
            File archive = new File(files.get(LoginStateSync.PART_NAME));
            try {
                LoginStateSync.restoreArchive(archive);
            } catch (Exception e) {
                SpiderDebug.log("sync", e);
                // 登录态归档里可能含学习到的 /sdcard 路径，没权限时同样写不进去。
                // 走的是同一句提示，不另起文案。
                if (warning == null && !fileAccess) warning = sharedStorageWarning();
            } finally {
                Path.clear(archive);
            }
        }
        backup.restore(options, force);
        if (options.isRemoteRelay()) RemoteStore.importRelayConfig(params.get("remoteRelay"));
        App.post(() -> Notify.show(R.string.sync_receive_success));
        return warning;
    }

    /**
     * 「应用数据已同步，但共享存储被跳过」——区分「还能救」和「救不了」，用户才知道下一步做什么。
     *
     * 别直接拿 Setting.hasFileManager() 当判据：它在 Android 10 及以下恒为 false，
     * 但那些系统是走运行时 READ/WRITE_EXTERNAL_STORAGE 的（见 PermissionUtil.requestFile），
     * 用户照样能在应用权限里开出来。真正无解的情况只有一种：
     * Android 11+ 且系统没提供「所有文件访问」设置页 —— 这时回退申请
     * WRITE_EXTERNAL_STORAGE 也没用，manifest 里它 maxSdkVersion=29。
     */
    private String sharedStorageWarning() {
        boolean grantable = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Setting.hasFileManager();
        return ResUtil.getString(grantable ? R.string.sync_shared_storage_skipped : R.string.sync_shared_storage_skipped_no_setting);
    }

    public void syncHistory(Map<String, String> params, boolean force) {
        Config config = Config.find(Config.objectFrom(params.get("config")));
        List<History> targets = History.arrayFrom(params.get("targets"));
        if (config.getUrl() == null) return;
        if (config.getUrl().equals(VodConfig.getUrl())) {
            if (force) History.delete(config.getId());
            History.sync(targets);
            RefreshEvent.history();
        } else {
            VodConfig.load(config, getCallback(targets, force, config.getId()));
        }
    }

    private Callback getCallback(List<History> targets, boolean force, int cid) {
        return new Callback() {
            @Override
            public void success() {
                if (force) History.delete(cid);
                History.sync(targets);
                RefreshEvent.history();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void syncKeep(Map<String, String> params, boolean force) {
        List<Keep> targets = Keep.arrayFrom(params.get("targets"));
        List<Config> configs = Config.arrayFrom(params.get("configs"));
        if (TextUtils.isEmpty(VodConfig.getUrl()) && !configs.isEmpty()) {
            VodConfig.load(Config.find(configs.get(0)), getCallback(configs, targets, force));
        } else {
            if (force) Keep.deleteAll();
            Keep.sync(configs, targets);
            RefreshEvent.keep();
        }
    }

    private Callback getCallback(List<Config> configs, List<Keep> targets, boolean force) {
        return new Callback() {
            @Override
            public void success() {
                if (force) Keep.deleteAll();
                Keep.sync(configs, targets);
                RefreshEvent.keep();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }
}
