package org.example.reviewer.diff;

import java.util.List;

/**
 * Один файл из diff'а. {@code addedLines} — номера строк в НОВОЙ версии файла,
 * только для добавленных строк: именно к ним GitHub разрешает привязать inline-комментарий.
 */
public record ChangedFile(String path, FileStatus status, String patch, List<Integer> addedLines) {

    public boolean reviewable() {
        return status != FileStatus.DELETED && status != FileStatus.BINARY && !addedLines.isEmpty();
    }
}
