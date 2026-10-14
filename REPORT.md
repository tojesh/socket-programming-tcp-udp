# File Transfer Using Java Sockets: TCP vs UDP

BCSE308P – Assessment 7: Socket Programming

## 1. Objective

Implement file transfer in Java using both TCP and UDP, transfer files of different sizes, measure transmission time and throughput, compare the results, and justify which protocol is more suitable for reliable file transfer.

## 2. Implementation

| File | Role |
|---|---|
| `TCPServer.java` / `TCPClient.java` | `ServerSocket`/`Socket` stream transfer. Header = file name (UTF) + size (long), then raw bytes in 8 KB chunks. Server sends a 1-byte confirmation when all bytes are saved. |
| `UDPServer.java` / `UDPClient.java` | `DatagramSocket` transfer. UDP gives no delivery guarantee, so reliability is added at application level with **stop-and-wait ARQ**: each packet carries a 4-byte sequence number and a 1024-byte payload; the receiver ACKs every packet; the sender retransmits after a 50 ms timeout; duplicates are re-ACKed but written once. Sequence 0 carries file name and size. |
| `Benchmark.java` | Generates random files (1 KB, 100 KB, 1 MB, 10 MB, 50 MB), runs both protocols over the loopback interface, takes the median of 5 runs (3 for 10/50 MB UDP) after one warm-up run, and checks every received file against the original with SHA-256. For UDP it also injects simulated packet loss (0%, 1%, 5%) by dropping outgoing datagrams at the sender. |

Timing is measured with `System.nanoTime()` on the client from connection start (TCP) or first packet (UDP) until the last byte is acknowledged. Throughput = file size / time.

Run manually:

```
javac *.java
java TCPServer 5000 received      # terminal 1
java TCPClient 127.0.0.1 5000 file.bin
java UDPServer 5001 received      # terminal 1
java UDPClient 127.0.0.1 5001 file.bin 0.0   # last arg = simulated loss rate
java Benchmark                    # full experiment, writes results.csv
```

## 3. Results

All transfers over loopback (127.0.0.1). Median values. Every received file matched its SHA-256 hash.

| Protocol | Size | Time (s) | Throughput (MB/s) | Retransmissions |
|---|---|---|---|---|
| TCP | 1 KB | 0.0012 | 0.87 | – |
| TCP | 100 KB | 0.0015 | 70.47 | – |
| TCP | 1 MB | 0.0028 | 369.68 | – |
| TCP | 10 MB | 0.0153 | 684.71 | – |
| TCP | 50 MB | 0.0581 | 902.35 | – |
| UDP, 0% loss | 1 KB | 0.0021 | 0.48 | 0 |
| UDP, 0% loss | 100 KB | 0.0076 | 13.40 | 0 |
| UDP, 0% loss | 1 MB | 0.0266 | 39.39 | 0 |
| UDP, 0% loss | 10 MB | 0.4101 | 25.57 | 0 |
| UDP, 0% loss | 50 MB | 1.8317 | 28.62 | 0 |
| UDP, 1% loss | 100 KB | 0.0093 | 11.01 | 0 |
| UDP, 1% loss | 1 MB | 0.4468 | 2.35 | 8 |
| UDP, 5% loss | 100 KB | 0.2608 | 0.39 | 5 |
| UDP, 5% loss | 1 MB | 2.1752 | 0.48 | 42 |

(Raw data: `results.csv`.)

## 4. Analysis

**TCP is much faster in the lossless case.** At 50 MB, TCP reached about 902 MB/s against about 29 MB/s for the UDP implementation, roughly 30 times higher. TCP streams data with a sliding window and large kernel buffers, so many segments are in flight at once. The stop-and-wait UDP sender sends one 1 KB packet and waits for its ACK before sending the next, so throughput is capped at about one payload per round trip regardless of how fast the link is. This is a property of my stop-and-wait design, not of UDP itself: a sliding-window or selective-repeat UDP protocol would close much of the gap, at the cost of reimplementing what TCP already provides.

**Throughput grows with file size for TCP.** Small files are dominated by fixed costs (connection setup, JVM and socket overhead), so the 1 KB result is low for both protocols. For larger files TCP's throughput keeps rising as those fixed costs are amortised and the window opens up. UDP stop-and-wait levels off near 25–40 MB/s because its per-packet cost stays constant.

**Loss is where reliability costs show.** With simulated loss, every dropped packet or ACK costs a full 50 ms timeout in my sender. At 1% loss, 1 MB took 0.447 s versus 0.027 s with no loss (about 17 times slower, 8 retransmissions). At 5% loss it took 2.18 s (42 retransmissions). The files still arrived intact in every case, which shows the ACK/retransmit logic works, but performance falls sharply.

**For a single small transfer, UDP has no advantage here.** Even at 1 KB, UDP was not faster than TCP in this setup (0.0021 s vs 0.0012 s), because the extra metadata packet and ACK round trips offset the saving from skipping the handshake.

## 5. Conclusion: which protocol suits reliable file transfer?

**TCP.** File transfer requires every byte to arrive, complete and in order. TCP guarantees this natively through acknowledgements, retransmission, ordering, flow control and congestion control, and in these experiments it was also far faster. To make UDP reliable I had to build sequencing, ACKs, timeouts, duplicate handling and retransmission myself, and the result was both more code and lower throughput; a good implementation would still be reimplementing TCP. UDP is better suited to workloads where timeliness matters more than completeness (live video, voice, games, DNS) or where an application wants its own transport behaviour.

## 6. Limitations

- All tests ran on the loopback interface on one machine, so there is effectively no real network latency, bandwidth limit, or loss. Absolute numbers (especially TCP's hundreds of MB/s) reflect in-memory kernel copies, not a real link; the relative comparison and the trends are what matter. Results on a real LAN/WAN would differ.
- Packet loss was simulated at the UDP sender only. TCP was not tested under loss; it would also slow down (retransmissions, congestion window reduction) but typically degrades far more gracefully than a fixed 50 ms stop-and-wait timeout.
- The UDP protocol is a simple stop-and-wait ARQ, chosen for clarity. Its throughput is not representative of an optimised UDP-based protocol.
- The 50 ms retransmission timeout was fixed rather than adaptive.
