package org.example.reviewer.vcs;

import java.util.List;

public interface ReviewPublisher {
    void publish(PullRequest pr, List<Finding> findings, String summary);
}
