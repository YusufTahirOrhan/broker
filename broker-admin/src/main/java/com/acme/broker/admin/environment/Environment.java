package com.acme.broker.admin.environment;

/** A row of {@code environment}: promotion order, approval and test settings come from the DB, never from code. */
public record Environment(String code, String name, int sortOrder, String adminApiUrl, boolean requiresApproval,
                          boolean testCallsAllowed) {
}
