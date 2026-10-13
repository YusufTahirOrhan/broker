package com.acme.broker.runtime.route;

/** Changes a freshly loaded snapshot before it is served; only the {@code local} profile has one. */
public interface SnapshotCustomizer {

    RouteSnapshot customize(RouteSnapshot loaded);

    /** True when the customization's own source changed and the snapshot should be rebuilt. */
    default boolean changed() {
        return false;
    }
}
