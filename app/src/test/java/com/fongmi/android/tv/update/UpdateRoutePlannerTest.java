package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * fork 定制：更新源已锁定为「本仓库 GitHub Release」，OCI 通道整体禁用。
 * 这里验证的核心是：无论偏好里存的是什么、OCI 元数据是否存在，都只规划 GitHub 一条路由。
 */
public class UpdateRoutePlannerTest {

    private static final String APK = "https://github.com/syzsky/webhtv/releases/download/v1/app.apk";

    private final OciArtifact artifact = new OciArtifact(
            "registry-1.docker.io",
            "fish2018/webhtv-apk",
            "v1-mobile-arm64_v8a",
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            1024);
    private final GithubProxy.Config direct = GithubProxy.resolve(GithubProxy.DIRECT, "", GithubProxy.MODE_FULL_URL);

    @Test
    public void ociSourceIsForcedBackToGithub() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.OCI,
                APK,
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(0).kind);
    }

    @Test
    public void githubSourceUsesGithubOnly() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.GITHUB,
                APK,
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(0).kind);
    }

    @Test
    public void legacyAutoIsForcedBackToGithub() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                "auto",
                APK,
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(0).kind);
    }

    @Test
    public void ociMetadataIsIgnoredEvenWhenPresent() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.OCI,
                APK,
                artifact,
                direct,
                "https://dockerproxy.net");
        assertTrue(routes.stream().noneMatch(route -> route.kind == UpdateTarget.Kind.OCI));
    }

    @Test
    public void missingGithubUrlYieldsNoRoute() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.GITHUB,
                "",
                artifact,
                direct,
                "https://dockerproxy.net");
        assertTrue(routes.isEmpty());
    }

    @Test
    public void normalizeAlwaysReturnsGithub() {
        assertEquals(UpdateSource.GITHUB, UpdateSource.normalize(UpdateSource.OCI));
        assertEquals(UpdateSource.GITHUB, UpdateSource.normalize("auto"));
        assertEquals(UpdateSource.GITHUB, UpdateSource.normalize(null));
        assertEquals(UpdateSource.GITHUB, UpdateSource.normalize(UpdateSource.GITHUB));
    }
}
