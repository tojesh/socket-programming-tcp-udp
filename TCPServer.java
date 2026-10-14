import java.io.*;
import java.net.*;

/** Receives one file over TCP. Protocol: UTF filename, long size, then raw bytes. */
public class TCPServer {
    public static final int BUF = 8192;

    /** Accepts one connection, saves the file into outDir, returns bytes received. */
    public static long receiveOne(ServerSocket ss, File outDir) throws IOException {
        try (Socket s = ss.accept();
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), BUF * 4))) {
            String name = in.readUTF();
            long size = in.readLong();
            File out = new File(outDir, "tcp_" + name);
            long got = 0;
            byte[] buf = new byte[BUF];
            try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(out), BUF * 4)) {
                while (got < size) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
                    if (n < 0) throw new EOFException("Connection closed early at " + got + "/" + size);
                    fos.write(buf, 0, n);
                    got += n;
                }
            }
            // Tell the sender everything arrived so it can stop its timer.
            s.getOutputStream().write(1);
            s.getOutputStream().flush();
            return got;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        File outDir = new File(args.length > 1 ? args[1] : "received");
        outDir.mkdirs();
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("TCP server listening on port " + port);
            while (true) {
                long n = receiveOne(ss, outDir);
                System.out.println("Received " + n + " bytes");
            }
        }
    }
}
