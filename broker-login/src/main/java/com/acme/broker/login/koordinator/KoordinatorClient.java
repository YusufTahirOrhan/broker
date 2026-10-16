package com.acme.broker.login.koordinator;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** The Koordinatör (the permission project): users' permissions and the projects that are the broker's applications. */
public interface KoordinatorClient {

    /** The user's permissions in the Koordinatör's order; empty when it did not answer (HTTP 200). */
    Optional<List<String>> permissions(String uid);

    /** The {@code code} of every project. */
    List<String> projectCodes() throws IOException;
}
