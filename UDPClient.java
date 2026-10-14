import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Sends one file over UDP with stop-and-wait ACKs and retransmission on timeout. */
public class UDPClient {
    public static final int TIMEOUT_MS = 50;
    public static final int MAX_RETRIES = 100;

    public static long retransmissions = 0; // from the most recent send() call

    /**
     * @param lossRate simulated probability (0..1) that an outgoing datagram is dropped, to exercise reliability
     * @return elapsed nanoseconds
     */
    public static long send(String host, int port, File f, double lossRate) throws IOException {
        retransmissions = 0;
        Random rnd = new Random(42);
        InetAddress addr = InetAddress.getByName(host);
        byte[] ackBuf = new byte[4];
        DatagramPacket ackPkt = new DatagramPacket(ackBuf, 4);

        long start = System.nanoTime();
        try (DatagramSocket sock = new DatagramSocket();
             InputStream fis = new BufferedInputStream(new FileInputStream(f), 65536)) {
            sock.setSoTimeout(TIMEOUT_MS);

            // seq 0: metadata
            byte[] nameBytes = f.getName().getBytes(StandardCharsets.UTF_8);
            ByteBuffer meta = ByteBuffer.allocate(4 + 8 + nameBytes.length);
            meta.putInt(0).putLong(f.length()).put(nameBytes);
            sendReliably(sock, addr, port, meta.array(), meta.position(), 0, ackPkt, ackBuf, lossRate, rnd);

            byte[] data = new byte[4 + UDPServer.PAYLOAD];
            int seq = 1, n;
            byte[] chunk = new byte[UDPServer.PAYLOAD];
            while ((n = fis.read(chunk)) > 0) {
                ByteBuffer.wrap(data).putInt(seq);
                System.arraycopy(chunk, 0, data, 4, n);
                sendReliably(sock, addr, port, data, 4 + n, seq, ackPkt, ackBuf, lossRate, rnd);
                seq++;
            }
        }
        return System.nanoTime() - start;
    }

    private static void sendReliably(DatagramSocket sock, InetAddress addr, int port, byte[] data, int len,
                                     int seq, DatagramPacket ackPkt, byte[] ackBuf,
                                     double lossRate, Random rnd) throws IOException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) retransmissions++;
            if (rnd.nextDouble() >= lossRate) {
                sock.send(new DatagramPacket(data, len, addr, port));
            }
            try {
                while (true) {
                    ackPkt.setLength(4);
                    sock.receive(ackPkt);
                    if (ByteBuffer.wrap(ackBuf).getInt() == seq) return;
                    // stale ACK for an earlier packet: ignore and keep waiting
                }
            } catch (SocketTimeoutException e) {
                // fall through and retransmit
            }
        }
        throw new IOException("Gave up on packet " + seq + " after " + MAX_RETRIES + " retries");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java UDPClient <host> <port> <file> [lossRate]");
            return;
        }
        File f = new File(args[2]);
        double loss = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        long ns = send(args[0], Integer.parseInt(args[1]), f, loss);
        double sec = ns / 1e9;
        System.out.printf("UDP: %d bytes in %.4f s = %.2f MB/s (retransmissions: %d)%n",
                f.length(), sec, f.length() / 1e6 / sec, retransmissions);
    }
}
