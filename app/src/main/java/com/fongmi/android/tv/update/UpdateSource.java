package com.fongmi.android.tv.update;

public final class UpdateSource {

    public static final String GITHUB = "github";
    public static final String OCI = "oci";

    private UpdateSource() {
    }

    /**
     * fork 定制：更新源锁定为本仓库（syzsky/webhtv）的 GitHub Release。
     * 上游作者那套 OCI 分发通道已整体禁用——无论偏好里存的是什么（含遗留的 "auto"），一律归一到 GitHub。
     */
    public static String normalize(String value) {
        return GITHUB;
    }
}
