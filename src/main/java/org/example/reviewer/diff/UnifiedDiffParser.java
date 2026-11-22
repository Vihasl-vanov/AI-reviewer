package org.example.reviewer.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбирает unified diff (вывод GitHub `Accept: application/vnd.github.v3.diff`). */
public final class UnifiedDiffParser {

    private static final Pattern HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private UnifiedDiffParser() {
    }

    public static List<ChangedFile> parse(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return files;
        }

        Builder current = null;
        int newLineNo = 0;

        for (String line : diff.split("\n", -1)) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new Builder(header.group(2));
                newLineNo = 0;
                continue;
            }
            if (current == null) {
                continue;
            }

            if (line.startsWith("new file mode")) {
                current.status = FileStatus.ADDED;
            } else if (line.startsWith("deleted file mode")) {
                current.status = FileStatus.DELETED;
            } else if (line.startsWith("rename to ")) {
                current.status = FileStatus.RENAMED;
                current.path = line.substring("rename to ".length()).trim();
            } else if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                current.status = FileStatus.BINARY;
            } else if (line.startsWith("index ") || line.startsWith("similarity index ")
                    || line.startsWith("rename from ") || line.startsWith("old mode")
                    || line.startsWith("new mode") || line.startsWith("--- ") || line.startsWith("+++ ")) {
                // служебные строки в промпт не нужны
            } else {
                Matcher hunk = HUNK.matcher(line);
                if (hunk.matches()) {
                    newLineNo = Integer.parseInt(hunk.group(1));
                    current.patch.add(line);
                } else if (line.startsWith("+")) {
                    current.addedLines.add(newLineNo++);
                    current.patch.add(line);
                } else if (line.startsWith("-")) {
                    current.patch.add(line);
                } else if (line.startsWith(" ")) {
                    newLineNo++;
                    current.patch.add(line);
                } else if (line.startsWith("\\")) {
                    current.patch.add(line);
                }
            }
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    private static final class Builder {
        private String path;
        private FileStatus status = FileStatus.MODIFIED;
        private final List<String> patch = new ArrayList<>();
        private final List<Integer> addedLines = new ArrayList<>();

        private Builder(String path) {
            this.path = path;
        }

        private ChangedFile build() {
            return new ChangedFile(path, status, String.join("\n", patch), List.copyOf(addedLines));
        }
    }
}
