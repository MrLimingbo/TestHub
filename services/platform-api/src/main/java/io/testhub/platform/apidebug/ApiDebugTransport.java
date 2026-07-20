package io.testhub.platform.apidebug;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public interface ApiDebugTransport {
    TransportResponse execute(String method, URI url, List<Header> headers, String body, int timeoutMs)
            throws IOException, InterruptedException;

    record Header(String name, String value) {
    }

    record TransportResponse(int statusCode, String statusText, List<Header> headers, String body, boolean truncated) {
    }
}
