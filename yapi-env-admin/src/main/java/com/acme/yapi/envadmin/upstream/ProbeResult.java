package com.acme.yapi.envadmin.upstream;

import java.util.List;

/**
 * Result of an OPTIONS probe. {@code result} is OK, WRONG_METHOD, PATH_NOT_FOUND or INCONCLUSIVE; {@code status} is
 * null when the service could not be reached.
 */
public record ProbeResult(String result, Integer status, List<String> allow, String url, String message) {

    public static final String OK = "OK";
    public static final String WRONG_METHOD = "WRONG_METHOD";
    public static final String PATH_NOT_FOUND = "PATH_NOT_FOUND";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";
}
