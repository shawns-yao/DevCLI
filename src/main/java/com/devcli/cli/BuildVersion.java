package com.devcli.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

/** 展示版本由 Maven 过滤资源提供，与产物版本共用来源。 */
final class BuildVersion {
    static final String VALUE = load();

    private BuildVersion() {}

    private static String load() {
        Properties properties = new Properties();
        try (var input = BuildVersion.class.getResourceAsStream("/devcli-build.properties")) {
            properties.load(input);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
