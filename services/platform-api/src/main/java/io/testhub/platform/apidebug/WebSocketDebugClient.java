package io.testhub.platform.apidebug;

import java.net.URI;
import java.util.List;

/**
 * Isolates the JDK WebSocket client so the control-plane workflow can be
 * exercised without opening a real network connection in tests.
 */
public interface WebSocketDebugClient {
    Connection connect(URI url, List<ApiDebugTransport.Header> headers, List<String> subprotocols, Listener listener) throws Exception;

    interface Connection {
        void sendText(String payload) throws Exception;

        void close(int statusCode, String reason) throws Exception;

        default String subprotocol() {
            return "";
        }
    }

    interface Listener {
        void onText(String payload);

        void onClosed(int statusCode, String reason);

        void onError();
    }
}
