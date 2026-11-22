package org.example.reviewer.diff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffParserTest {

    @Test
    void parsesModifiedFileWithAddedLineNumbers() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                index 1111111..2222222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -10,3 +10,4 @@ class A {
                     int x = 1;
                +    int y = 2;
                     int z = 3;
                -    int old = 4;
                """;

        List<ChangedFile> files = UnifiedDiffParser.parse(diff);

        assertEquals(1, files.size());
        ChangedFile file = files.get(0);
        assertEquals("src/A.java", file.path());
        assertEquals(FileStatus.MODIFIED, file.status());
        assertEquals(List.of(11), file.addedLines());
        assertTrue(file.patch().contains("@@ -10,3 +10,4 @@"));
    }

    @Test
    void numbersLinesFromEachHunkHeader() {
        String diff = """
                diff --git a/A.txt b/A.txt
                --- a/A.txt
                +++ b/A.txt
                @@ -1,2 +1,3 @@
                 keep
                +added at 2
                 keep
                @@ -40,2 +41,3 @@
                 keep
                +added at 42
                 keep
                """;

        assertEquals(List.of(2, 42), UnifiedDiffParser.parse(diff).get(0).addedLines());
    }

    @Test
    void marksNewFileAsAdded() {
        String diff = """
                diff --git a/New.java b/New.java
                new file mode 100644
                --- /dev/null
                +++ b/New.java
                @@ -0,0 +1,2 @@
                +line one
                +line two
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.ADDED, file.status());
        assertEquals(List.of(1, 2), file.addedLines());
    }

    @Test
    void marksDeletedFileAndCollectsNoAddedLines() {
        String diff = """
                diff --git a/Gone.java b/Gone.java
                deleted file mode 100644
                --- a/Gone.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -line one
                -line two
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.DELETED, file.status());
        assertTrue(file.addedLines().isEmpty());
    }

    @Test
    void marksBinaryFile() {
        String diff = """
                diff --git a/logo.png b/logo.png
                index 1111111..2222222 100644
                Binary files a/logo.png and b/logo.png differ
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.BINARY, file.status());
        assertTrue(file.addedLines().isEmpty());
    }

    @Test
    void usesNewPathForRenamedFile() {
        String diff = """
                diff --git a/old/Name.java b/new/Name.java
                similarity index 95%
                rename from old/Name.java
                rename to new/Name.java
                --- a/old/Name.java
                +++ b/new/Name.java
                @@ -1,1 +1,2 @@
                 keep
                +added
                """;

        ChangedFile file = UnifiedDiffParser.parse(diff).get(0);
        assertEquals(FileStatus.RENAMED, file.status());
        assertEquals("new/Name.java", file.path());
        assertEquals(List.of(2), file.addedLines());
    }

    @Test
    void parsesSeveralFilesInOneDiff() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,1 +1,2 @@
                 keep
                +a
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -1,1 +1,2 @@
                 keep
                +b
                """;

        List<ChangedFile> files = UnifiedDiffParser.parse(diff);
        assertEquals(List.of("A.java", "B.java"), files.stream().map(ChangedFile::path).toList());
    }

    @Test
    void returnsEmptyListForBlankDiff() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty());
    }
}
