package com.acme.yapi.admin.promotion;

import java.util.Arrays;
import java.util.Optional;

/** What a promotion request moves, with the columns of {@code promotion_request} that name it. */
public enum SubjectType {
    ROUTE("definition_id", "version"),
    PROFILE("profile_id", "profile_version"),
    PLUGIN("plugin_id", "plugin_version");

    private final String idColumn;
    private final String versionColumn;

    SubjectType(String idColumn, String versionColumn) {
        this.idColumn = idColumn;
        this.versionColumn = versionColumn;
    }

    public String idColumn() {
        return idColumn;
    }

    public String versionColumn() {
        return versionColumn;
    }

    public static Optional<SubjectType> parse(String text) {
        return Arrays.stream(values()).filter(type -> type.name().equals(text)).findFirst();
    }
}
