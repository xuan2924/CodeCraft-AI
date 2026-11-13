package ai.codecraft.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** A module-owned JSON input bound; return zero when this policy does not apply. */
@FunctionalInterface
public interface RequestBodyPolicy {
    long maximumBytes(HttpServletRequest request);
}
