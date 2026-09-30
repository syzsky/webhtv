package com.fongmi.android.tv.update;

import java.util.ArrayList;
import java.util.List;

public final class UpdateRoutePlanner {

    private UpdateRoutePlanner() {
    }

    /**
     * fork 定制：只规划「本仓库 GitHub Release」这一条下载路由，不再回退到上游作者的 OCI 镜像。
     * 参数签名保持不变，避免调用方与单测大改。
     */
    public static List<UpdateTarget> plan(String source, String githubUrl, OciArtifact artifact, GithubProxy.Config githubProxy, String ociEndpoint) {
        List<UpdateTarget> routes = new ArrayList<>();
        addGithub(routes, githubUrl, githubProxy);
        return routes;
    }

    private static void addGithub(List<UpdateTarget> routes, String githubUrl, GithubProxy.Config proxy) {
        if (githubUrl == null || githubUrl.trim().isEmpty()) return;
        try {
            routes.add(UpdateTarget.github(proxy.rewrite(githubUrl)));
        } catch (Exception ignored) {
        }
    }
}
