import java.io.*;
import java.net.*;

/** Sends one file over TCP and reports time and throughput. */
public class TCPClient {
    /** Returns elapsed nanoseconds, measured from connect to the server's final ACK byte. */
    public static long send(String host, int port, File f) throws IOException {
        byte[] buf = new byte[TCPServer.BUF];
        long start = System.nanoTime();
        try (Socket s = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), buf.length * 4));
             InputStream fis = new BufferedInputStream(new FileInputStream(f), buf.length * 4)) {
            out.writeUTF(f.getName());
            out.writeLong(f.length());
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            if (s.getInputStream().read() != 1) throw new IOException("No confirmation from server");
        }
        return System.nanoTime() - start;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java TCPClient <host> <port> <file>");
            return;
        }
        File f = new File(args[2]);
        long ns = send(args[0], Integer.parseInt(args[1]), f);
        double sec = ns / 1e9;
        System.out.printf("TCP: %d bytes in %.4f s = %.2f MB/s%n", f.length(), sec, f.length() / 1e6 / sec);
    }
}
