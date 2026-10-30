package com.acme.yapi.core.plugin;

/** A plugin ready to apply: its name, the version an environment serves and its parsed definition. */
public record Plugin(String name, int version, PluginDefinition definition) {

    /** What a log event records of it. */
    public Applied applied() {
        return new Applied(name, version);
    }

    public record Applied(String name, int version) {
    }
}
