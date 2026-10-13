package com.acme.broker.envadmin.runtime;

/** Outcome of writing a snapshot to the runtime DB. */
public sealed interface ApplyResult {

    /** The snapshot was written and the config version moved to {@code configVersion}. */
    record Applied(long configVersion) implements ApplyResult {
    }

    /** The same promotion was already written: a retried call after a lost response. Nothing changed. */
    record AlreadyApplied(long configVersion) implements ApplyResult {
    }

    /** A newer promotion is already in place; an older or delayed request cannot overwrite it. */
    record Stale(long currentPromotionId, long configVersion) implements ApplyResult {
    }

    /** What the snapshot refers to is not in this environment. */
    record Missing(String code, String message) implements ApplyResult {
    }
}
