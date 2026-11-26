package io.floci.oci.services.nosql.query;

/**
 * A DDL or SQL statement floci-oci cannot parse, or uses a construct outside the supported
 * subset. The service turns it into {@code 400 InvalidParameter}; SummarizeStatement reports
 * its message as {@code syntaxError} instead.
 */
public class NosqlSyntaxException extends RuntimeException {

    public NosqlSyntaxException(String message) {
        super(message);
    }
}
