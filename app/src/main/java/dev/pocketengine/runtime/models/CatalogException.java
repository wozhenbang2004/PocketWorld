package dev.pocketengine.runtime.models;

import java.io.IOException;

/** Body-free failure suitable for native status. Raw server errors may echo credentials. */
public final class CatalogException extends IOException {
    public enum Code { AUTH_REQUIRED, RATE_LIMITED, HTTP_ERROR, REDIRECT_REFUSED, NETWORK_ERROR,
        INVALID_CATALOG, PAGINATION_INCOMPLETE, TOO_LARGE, CANCELLED, CACHE_INVALID }
    public final Code code;
    public final int httpStatus;
    public CatalogException(Code code) { this(code, 0); }
    public CatalogException(Code code, int httpStatus) {
        super("model_catalog_" + code.name().toLowerCase(java.util.Locale.ROOT));
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
