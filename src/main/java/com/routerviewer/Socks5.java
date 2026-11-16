package com.routerviewer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal SOCKS5 server (RFC 1928): no auth, CONNECT and UDP ASSOCIATE, hostnames resolved on this device.
 * Pure java.net so it can be checked with: java -ea Socks5.java
 */
final class Socks5 {
    private Socks5() {}

    static final AtomicInteger active = new AtomicInteger();
    private static final Set<Socket> open = ConcurrentHashMap.newKeySet();
    static int maxSessions = 256;
    static int handshakeMs = 10_000;
    static int udpIdleMs = 300_000;

    // ponytail: one thread per direction per connection, capped at maxSessions; use NIO if it ever serves many
    static void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket c = server.accept();
                if (active.get() >= maxSessions) {
                    c.close();
                    continue;
                }
                active.incrementAndGet();
                new Thread(() -> session(c, server)).start();
            } catch (IOException ignored) {
            }
        }
    }

    /** Closes every client and outgoing TCP socket, which ends all sessions including UDP relays. */
    static void closeAll() {
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void session(Socket c, ServerSocket server) {
        open.add(c);
        try (Socket client = c) {
            // Callers close the server before closeAll(), so a session registered too late for closeAll() sees it closed here.
            if (server.isClosed()) return;
            client.setSoTimeout(handshakeMs);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            byte[] methods = new byte[in.readUnsignedByte()];
            in.readFully(methods);
            boolean noAuth = false;
            for (byte m : methods) if (m == 0) noAuth = true;
            out.write(new byte[] {5, noAuth ? 0 : (byte) 0xFF});
            if (!noAuth) return;

            if (in.readUnsignedByte() != 5) return;
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte();
            String host = readHost(in);
            int port = in.readUnsignedShort();
            if (cmd == 3) {
                associate(client, in, out);
                return;
            }
            if (cmd != 1) {
                reply(out, 7);
                return;
            }
            Socket remote = new Socket();
            open.add(remote);
            try (Socket r = remote) {
                try {
                    remote.connect(new InetSocketAddress(host, port), 10_000);
                } catch (UnknownHostException e) {
                    reply(out, 4);
                    return;
                } catch (IOException e) {
                    reply(out, 5);
                    return;
                }
                reply(out, 0);
                client.setSoTimeout(0);
                Thread up = new Thread(() -> pump(client, remote));
                up.start();
                pump(remote, client);
                up.join();
            } finally {
                open.remove(remote);
            }
        } catch (Exception ignored) {
        } finally {
            open.remove(c);
            active.decrementAndGet();
        }
    }

    /** UDP ASSOCIATE: relays datagrams until the client closes the control connection or no datagram moves for udpIdleMs. */
    private static void associate(Socket client, InputStream in, OutputStream out) throws IOException {
        InetAddress owner = client.getInetAddress();
        AtomicReference<SocketAddress> peer = new AtomicReference<>();
        AtomicLong last = new AtomicLong(System.currentTimeMillis());
        try (DatagramSocket relay = new DatagramSocket(0, client.getLocalAddress());
             DatagramSocket remote = new DatagramSocket()) {
            new Thread(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (true) {
                        p.setLength(buf.length);
                        relay.receive(p);
                        if (!p.getAddress().equals(owner)) continue;
                        last.set(System.currentTimeMillis());
                        peer.set(p.getSocketAddress());
                        try {
                            DataInputStream d = new DataInputStream(new ByteArrayInputStream(buf, 0, p.getLength()));
                            d.readUnsignedShort();
                            if (d.readUnsignedByte() != 0) continue;
                            String host = readHost(d);
                            int port = d.readUnsignedShort();
                            int off = p.getLength() - d.available();
                            remote.send(new DatagramPacket(buf, off, p.getLength() - off, InetAddress.getByName(host), port));
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            }).start();
            new Thread(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (true) {
                        p.setLength(buf.length);
                        remote.receive(p);
                        last.set(System.currentTimeMillis());
                        SocketAddress to = peer.get();
                        if (to == null) continue;
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        b.write(new byte[] {0, 0, 0});
                        writeAddress(b, p.getAddress(), p.getPort());
                        b.write(buf, 0, p.getLength());
                        relay.send(new DatagramPacket(b.toByteArray(), b.size(), to));
                    }
                } catch (IOException ignored) {
                }
            }).start();
            reply(out, 0, relay.getLocalAddress(), relay.getLocalPort());
            client.setSoTimeout(Math.min(60_000, udpIdleMs));
            while (true) {
                try {
                    if (in.read() < 0) return;
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() - last.get() >= udpIdleMs) return;
                }
            }
        }
    }

    private static void writeAddress(ByteArrayOutputStream b, InetAddress a, int port) {
        byte[] raw = a.getAddress();
        b.write(raw.length == 4 ? 1 : 4);
        b.write(raw, 0, raw.length);
        b.write(port >> 8);
        b.write(port);
    }

    private static String readHost(DataInputStream in) throws IOException {
        int atyp = in.readUnsignedByte();
        byte[] a;
        switch (atyp) {
            case 1: a = new byte[4]; break;
            case 4: a = new byte[16]; break;
            case 3:
                a = new byte[in.readUnsignedByte()];
                in.readFully(a);
                return new String(a, StandardCharsets.US_ASCII);
            default: throw new IOException("bad address type " + atyp);
        }
        in.readFully(a);
        return InetAddress.getByAddress(a).getHostAddress();
    }

    private static void reply(OutputStream out, int code) throws IOException {
        reply(out, code, InetAddress.getByAddress(new byte[4]), 0);
    }

    private static void reply(OutputStream out, int code, InetAddress bound, int port) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[] {5, (byte) code, 0});
        writeAddress(b, bound, port);
        out.write(b.toByteArray());
        out.flush();
    }

    private static void pump(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException ignored) {
        }
        try {
            to.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try (ServerSocket echo = new ServerSocket(0, 50, lo); ServerSocket proxy = new ServerSocket(0, 50, lo)) {
            new Thread(() -> {
                while (true) {
                    try {
                        Socket s = echo.accept();
                        new Thread(() -> pump(s, s)).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }).start();
            new Thread(() -> serve(proxy)).start();

            byte[] ipv4 = {5, 1, 0, 1, 127, 0, 0, 1, (byte) (echo.getLocalPort() >> 8), (byte) echo.getLocalPort()};
            check(roundTrip(proxy, ipv4, "hello") == 0, "CONNECT by IPv4");

            byte[] name = "127.0.0.1".getBytes(StandardCharsets.US_ASCII);
            byte[] domain = new byte[7 + name.length];
            domain[0] = 5; domain[1] = 1; domain[3] = 3; domain[4] = (byte) name.length;
            System.arraycopy(name, 0, domain, 5, name.length);
            domain[5 + name.length] = (byte) (echo.getLocalPort() >> 8);
            domain[6 + name.length] = (byte) echo.getLocalPort();
            check(roundTrip(proxy, domain, "world") == 0, "CONNECT by hostname");

            byte[] bind = ipv4.clone();
            bind[1] = 2;
            check(roundTrip(proxy, bind, null) == 7, "BIND rejected");

            byte[] closed = ipv4.clone();
            closed[8] = 0; closed[9] = 1;
            check(roundTrip(proxy, closed, null) == 5, "refused port reported");

            try (DatagramSocket udpEcho = new DatagramSocket(0, lo)) {
                new Thread(() -> {
                    byte[] b = new byte[2048];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    try {
                        while (true) {
                            p.setLength(b.length);
                            udpEcho.receive(p);
                            udpEcho.send(p);
                        }
                    } catch (IOException ignored) {
                    }
                }).start();
                udpRoundTrip(proxy, udpEcho.getLocalPort());
            }

            handshakeMs = 300;
            try (Socket idle = new Socket(proxy.getInetAddress(), proxy.getLocalPort())) {
                idle.setSoTimeout(3000);
                check(idle.getInputStream().read() < 0, "silent client dropped after handshake timeout");
            }
            handshakeMs = 10_000;

            awaitActive(0);
            maxSessions = 1;
            try (Socket first = new Socket(proxy.getInetAddress(), proxy.getLocalPort());
                 Socket second = new Socket(proxy.getInetAddress(), proxy.getLocalPort())) {
                second.setSoTimeout(3000);
                check(second.getInputStream().read() < 0, "connection over the limit refused");
            }
            maxSessions = 256;

            awaitActive(0);
            try (Socket s = new Socket(proxy.getInetAddress(), proxy.getLocalPort())) {
                s.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                s.getOutputStream().write(new byte[] {5, 1, 0});
                in.readUnsignedShort();
                s.getOutputStream().write(ipv4);
                in.readFully(new byte[10]);
                awaitActive(1);
                closeAll();
                check(in.read() < 0, "closeAll ends a relaying session");
            }

            awaitActive(0);
            udpIdleMs = 300;
            try (Socket s = new Socket(proxy.getInetAddress(), proxy.getLocalPort())) {
                s.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                s.getOutputStream().write(new byte[] {5, 1, 0});
                in.readUnsignedShort();
                s.getOutputStream().write(new byte[] {5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
                in.readFully(new byte[10]);
                check(in.read() < 0, "idle UDP relay closed");
            }
            udpIdleMs = 300_000;
            awaitActive(0);

            try (ServerSocket late = new ServerSocket(0, 1, lo);
                 Socket s = new Socket(lo, late.getLocalPort())) {
                Socket c = late.accept();
                late.close();
                active.incrementAndGet();
                new Thread(() -> session(c, late)).start();
                s.setSoTimeout(3000);
                check(s.getInputStream().read() < 0, "session accepted while stopping is closed");
            }
            awaitActive(0);
        }
        System.out.println("Socks5 OK");
        System.exit(0);
    }

    private static int roundTrip(ServerSocket proxy, byte[] request, String payload) throws IOException {
        try (Socket s = new Socket(proxy.getInetAddress(), proxy.getLocalPort())) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            out.write(new byte[] {5, 1, 0});
            check(in.readUnsignedByte() == 5 && in.readUnsignedByte() == 0, "greeting");
            out.write(request);
            byte[] rep = new byte[10];
            in.readFully(rep);
            if (rep[1] == 0 && payload != null) {
                out.write(payload.getBytes(StandardCharsets.US_ASCII));
                byte[] back = new byte[payload.length()];
                in.readFully(back);
                check(payload.equals(new String(back, StandardCharsets.US_ASCII)), "relay " + payload);
            }
            return rep[1];
        }
    }

    private static void udpRoundTrip(ServerSocket proxy, int echoPort) throws IOException {
        try (Socket s = new Socket(proxy.getInetAddress(), proxy.getLocalPort());
             DatagramSocket u = new DatagramSocket(0, proxy.getInetAddress())) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            out.write(new byte[] {5, 1, 0});
            check(in.readUnsignedByte() == 5 && in.readUnsignedByte() == 0, "greeting");
            out.write(new byte[] {5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            byte[] rep = new byte[10];
            in.readFully(rep);
            check(rep[1] == 0, "UDP ASSOCIATE accepted");
            InetAddress relay = InetAddress.getByAddress(new byte[] {rep[4], rep[5], rep[6], rep[7]});
            int relayPort = ((rep[8] & 0xFF) << 8) | (rep[9] & 0xFF);

            byte[] head = {0, 0, 0, 1, 127, 0, 0, 1, (byte) (echoPort >> 8), (byte) echoPort};
            byte[] msg = new byte[head.length + 3];
            System.arraycopy(head, 0, msg, 0, head.length);
            System.arraycopy("udp".getBytes(StandardCharsets.US_ASCII), 0, msg, head.length, 3);
            u.setSoTimeout(3000);
            u.send(new DatagramPacket(msg, msg.length, relay, relayPort));
            byte[] back = new byte[64];
            DatagramPacket p = new DatagramPacket(back, back.length);
            u.receive(p);
            check(p.getLength() == msg.length, "UDP reply length");
            for (int i = 0; i < msg.length; i++) check(back[i] == msg[i], "UDP reply byte " + i);
        }
    }

    private static void awaitActive(int n) throws InterruptedException {
        for (int i = 0; i < 100 && active.get() != n; i++) Thread.sleep(20);
        check(active.get() == n, "active sessions " + active.get() + ", expected " + n);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
