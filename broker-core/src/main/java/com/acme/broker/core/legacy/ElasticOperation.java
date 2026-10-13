package com.acme.broker.core.legacy;

import tools.jackson.databind.node.ObjectNode;

/**
 * One line pair of an Elasticsearch bulk request. {@code UPSERT} merges a partial document into {@code _id}
 * ({@code update} with {@code doc_as_upsert}), so REQUEST and RESPONSE can arrive in any order and a replayed event
 * changes nothing. {@code INDEX} writes a whole document under a deterministic {@code _id}.
 */
public record ElasticOperation(Kind kind, String index, String id, ObjectNode document) {

    public enum Kind {
        UPSERT, INDEX
    }
}
