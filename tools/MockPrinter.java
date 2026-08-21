/**
 * A stand-in for a TSPL printer, so the bridge can be exercised without hardware.
 *
 * <p>Listens on a raw socket, accepts one connection at a time exactly like the real thing, and
 * dumps whatever bytes arrive. Run several on different ports to rehearse multi-printer load.
 *
 * <pre>java tools/MockPrinter.java 9100</pre>
 */
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;

public final class MockPrinter {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;
        boolean quiet = args.length > 1 && args[1].equals("--quiet");
        try (ServerSocket server = new ServerSocket(port)) {
            log("mock printer listening on :" + port + " (one connection at a time, like real TSPL hardware)");
            while (true) {
                try (Socket socket = server.accept()) {
                    log("connected: " + socket.getRemoteSocketAddress());
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (!quiet) {
                            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                            log("received " + n + " bytes:");
                            System.out.println(indent(text));
                        }
                    }
                    log("disconnected after " + total + " bytes");
                } catch (Exception e) {
                    log("connection error: " + e);
                }
            }
        }
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            sb.append("    | ").append(line).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + msg);
    }
}
