package com.acme.yapi.login.koordinator;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** The Koordinatör (the permission project): users' permissions and the projects that are the broker's applications. */
public interface KoordinatorClient {

    /**
     * The user's active permissions in the Koordinatör's order, without repeats; empty when it did not answer with
     * HTTP 200 and the expected shape.
     */
    Optional<List<String>> permissions(String uid);

    /** The application names of the projects that use Yapi. */
    List<String> applications() throws IOException;
}
