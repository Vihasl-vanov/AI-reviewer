package org.example.reviewer.vcs;

public interface PullRequestSource {
    PullRequest fetchPullRequest(String owner, String repo, int number);

    String fetchDiff(String owner, String repo, int number);
}
