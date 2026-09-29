package com.fongmi.android.tv.gitcloud.provider;

import com.fongmi.android.tv.gitcloud.GitProviderType;

public final class GitCloudProviders {

    private static final GitHubProvider GITHUB = new GitHubProvider();

    private GitCloudProviders() {
    }

    public static GitCloudProvider get(GitProviderType type) {
        return GITHUB;
    }
}
