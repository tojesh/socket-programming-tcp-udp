import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Receives one file over UDP using a stop-and-wait scheme.
 * Packet layout: [int seq][payload]. seq 0 is metadata: [long size][name bytes].
 * Data packets are seq 1..N. Every packet is answered with a 4-byte ACK carrying its seq.
 * Duplicates are re-ACKed but not written twice.
 */
public class UDPServer {
    public static final int PAYLOAD = 1024;

    /** Receives one file into outDir, returns bytes received. */
    public static long receiveOne(DatagramSocket sock, File outDir) throws IOException {
        byte[] buf = new byte[PAYLOAD + 4];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        sock.setSoTimeout(0);

        long size = -1, got = 0;
        int expected = 0; // next seq we need (0 = metadata)
        OutputStream fos = null;
        InetAddress peer = null;
        int peerPort = -1;
        byte[] ack = new byte[4];

        try {
            while (true) {
                try {
                    p.setLength(buf.length);
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    break; // linger period over after the file completed
                }
                ByteBuffer bb = ByteBuffer.wrap(p.getData(), 0, p.getLength());
                int seq = bb.getInt();
                peer = p.getAddress();
                peerPort = p.getPort();

                if (seq == expected) {
                    if (seq == 0) {
                        size = bb.getLong();
                        byte[] nameBytes = new byte[bb.remaining()];
                        bb.get(nameBytes);
                        String name = new String(nameBytes, StandardCharsets.UTF_8);
                        fos = new BufferedOutputStream(new FileOutputStream(new File(outDir, "udp_" + name)), 65536);
                    } else {
                        int len = bb.remaining();
                        fos.write(p.getData(), 4, len);
                        got += len;
                    }
                    expected++;
                }
                // ACK new and duplicate packets alike (a duplicate means our ACK was lost).
                if (seq < expected) {
                    ByteBuffer.wrap(ack).putInt(seq);
                    sock.send(new DatagramPacket(ack, 4, peer, peerPort));
                }
                if (size >= 0 && got >= size) {
                    // Stay briefly to re-ACK the last packet if our ACK was lost.
                    sock.setSoTimeout(300);
                }
            }
        } finally {
            if (fos != null) fos.close();
        }
        return got;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5001;
        File outDir = new File(args.length > 1 ? args[1] : "received");
        outDir.mkdirs();
        try (DatagramSocket sock = new DatagramSocket(port)) {
            System.out.println("UDP server listening on port " + port);
            while (true) {
                long n = receiveOne(sock, outDir);
                System.out.println("Received " + n + " bytes");
            }
        }
    }
}
