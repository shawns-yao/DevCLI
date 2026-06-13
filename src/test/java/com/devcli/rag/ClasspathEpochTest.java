package com.devcli.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClasspathEpochTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsBuildDescriptorChanges() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, "<project><dependencies/></project>");

        ClasspathEpoch first = ClasspathEpoch.detect(tempDir);
        Files.writeString(pom, "<project><dependencies><dependency/></dependencies></project>");
        ClasspathEpoch second = ClasspathEpoch.detect(tempDir);

        assertNotEquals("none", first.value());
        assertNotEquals(first.value(), second.value());
    }

    @Test
    void returnsNoneWhenNoBuildDescriptorExists() {
        assertEquals("none", ClasspathEpoch.detect(tempDir).value());
    }
}
