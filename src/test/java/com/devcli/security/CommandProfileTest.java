package com.devcli.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandProfileTest {
    @Test
    void classifiesMavenGoalsWithoutConfusingSkipTestsProperty() {
        assertEquals(CommandProfile.MAVEN_COMPILE,
                CommandProfile.classify("mvn -q -DskipTests package"));
        assertEquals(CommandProfile.MAVEN_TEST,
                CommandProfile.classify("mvn -q -Dtest=UnitTest test"));
        assertEquals(CommandProfile.MAVEN_TEST,
                CommandProfile.classify("mvn test"));
        assertEquals(CommandProfile.PROJECT_BUILD,
                CommandProfile.classify("mvn -q dependency:tree"));
    }

    @Test
    void recognizesWindowsBuildLaunchers() {
        assertEquals(CommandProfile.MAVEN_COMPILE,
                CommandProfile.classify("mvn.cmd -DskipTests package"));
        assertEquals(CommandProfile.PROJECT_BUILD,
                CommandProfile.classify("gradlew.bat build"));
        assertEquals(CommandProfile.PROJECT_BUILD,
                CommandProfile.classify("npm.cmd run build"));
    }
}
