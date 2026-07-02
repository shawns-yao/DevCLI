package com.devcli.tool.provider;

import java.util.List;

public record ToolParameter(String name, String type, String description, boolean required, List<String> enumValues) {
    public ToolParameter(String name, String type, String description, boolean required) {
        this(name, type, description, required, List.of());
    }

    public ToolParameter(String name, String type, String description, boolean required, String... enumValues) {
        this(name, type, description, required,
                enumValues == null || enumValues.length == 0 ? List.of() : List.of(enumValues));
    }
}
