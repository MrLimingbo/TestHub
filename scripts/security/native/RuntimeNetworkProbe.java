import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Checks service aliases using the same JVM/glibc resolver as the application. */
public class RuntimeNetworkProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("serve")) {
            try (var server = new ServerSocket(8080)) {
                System.out.println("fixture ready");
                System.out.flush();
                while (true) {
                    try (var socket = server.accept()) {
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                    }
                }
            }
        }
        if (args.length == 1 && args[0].equals("snapshot")) {
            if (InetAddress.getAllByName("mysql-svc").length == 0) throw new AssertionError("Initial resolver lookup failed");
            System.out.println("snapshot ready");
            System.out.flush();
            if (System.in.read() < 0) throw new AssertionError("Missing reload signal");
            if (!Files.readString(Path.of("/etc/resolv.conf")).contains("changed.test")) throw new AssertionError("Resolver fixture was not updated");
            args = new String[]{"redis-svc"};
        }
        for (String host : args) {
            if (InetAddress.getAllByName(host).length == 0) throw new AssertionError("No address for " + host);
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, 8080), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: qa\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
                if (!response.contains("200")) throw new AssertionError("Unexpected fixture response for " + host);
            }
            System.out.println(host + " DNS and TCP check passed");
        }
    }
}
