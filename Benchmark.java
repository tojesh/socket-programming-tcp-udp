import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Runs TCP and UDP transfers over loopback for several file sizes, several runs each,
 * verifies integrity with SHA-256, and writes results.csv.
 */
public class Benchmark {
    static final int RUNS = 5;
    static final int TCP_PORT = 5000, UDP_PORT = 5001;

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static File makeFile(File dir, String name, long size) throws IOException {
        File f = new File(dir, name);
        Random r = new Random(size);
        byte[] b = new byte[65536];
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
            long left = size;
            while (left > 0) {
                r.nextBytes(b);
                int n = (int) Math.min(b.length, left);
                o.write(b, 0, n);
                left -= n;
            }
        }
        return f;
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    public static void main(String[] args) throws Exception {
        File work = new File("bench_data");
        File recv = new File(work, "received");
        recv.mkdirs();
        long[] sizes = {1L << 10, 100L << 10, 1L << 20, 10L << 20, 50L << 20};
        String[] labels = {"1 KB", "100 KB", "1 MB", "10 MB", "50 MB"};
        // scenarios: protocol, simulated UDP loss rate
        double[] udpLoss = {0.0, 0.01, 0.05};

        ExecutorService pool = Executors.newCachedThreadPool();
        PrintWriter csv = new PrintWriter(new FileWriter("results.csv"));
        csv.println("protocol,size_label,size_bytes,median_time_s,median_throughput_MBps,retransmissions,integrity_ok");
        System.out.printf("%-14s %-8s %12s %16s %8s %s%n", "Protocol", "Size", "Time (s)", "Throughput MB/s", "Retx", "Integrity");

        for (int i = 0; i < sizes.length; i++) {
            File src = makeFile(work, "file_" + labels[i].replace(" ", "") + ".bin", sizes[i]);
            String hash = sha256(src);

            // ---- TCP ----
            try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
                List<Double> times = new ArrayList<>();
                boolean ok = true;
                for (int r = 0; r < RUNS + 1; r++) { // first run is warm-up
                    Future<Long> srv = pool.submit(() -> TCPServer.receiveOne(ss, recv));
                    long ns = TCPClient.send("127.0.0.1", TCP_PORT, src);
                    srv.get();
                    ok &= sha256(new File(recv, "tcp_" + src.getName())).equals(hash);
                    if (r > 0) times.add(ns / 1e9);
                }
                double t = median(times);
                report(csv, "TCP", labels[i], sizes[i], t, 0, ok);
            }

            // ---- UDP at several simulated loss rates ----
            for (double loss : udpLoss) {
                // keep lossy runs of large files short: 50 MB at 5% loss would take very long
                if (loss > 0 && sizes[i] > (1L << 20)) continue;
                try (DatagramSocket ds = new DatagramSocket(UDP_PORT)) {
                    List<Double> times = new ArrayList<>();
                    long retx = 0;
                    boolean ok = true;
                    int runs = sizes[i] >= (10L << 20) ? 3 : RUNS;
                    for (int r = 0; r < runs + 1; r++) {
                        Future<Long> srv = pool.submit(() -> UDPServer.receiveOne(ds, recv));
                        long ns = UDPClient.send("127.0.0.1", UDP_PORT, src, loss);
                        long rt = UDPClient.retransmissions;
                        srv.get();
                        ok &= sha256(new File(recv, "udp_" + src.getName())).equals(hash);
                        if (r > 0) { times.add(ns / 1e9); retx += rt; }
                    }
                    double t = median(times);
                    report(csv, loss == 0 ? "UDP (0% loss)" : "UDP (" + (int) (loss * 100) + "% loss)",
                            labels[i], sizes[i], t, retx / times.size(), ok);
                }
            }
        }
        csv.close();
        pool.shutdownNow();
    }

    static void report(PrintWriter csv, String proto, String label, long size, double t, long retx, boolean ok) {
        double mbps = size / 1e6 / t;
        System.out.printf("%-14s %-8s %12.4f %16.2f %8d %s%n", proto, label, t, mbps, retx, ok ? "OK" : "FAIL");
        csv.printf("%s,%s,%d,%.6f,%.3f,%d,%s%n", proto, label, size, t, mbps, retx, ok);
        csv.flush();
    }
}
