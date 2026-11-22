package org.example.reviewer.vcs;

public record PullRequest(String owner, String repo, int number, String title, String description, String headSha) {

    public String slug() {
        return owner + "/" + repo + "#" + number;
    }
}
