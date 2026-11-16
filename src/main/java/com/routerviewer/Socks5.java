package com.routerviewer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal SOCKS5 server (RFC 1928): no auth, CONNECT only, hostnames resolved on this device.
 * Pure java.net so it can be checked with: java -ea Socks5.java
 */
final class Socks5 {
    private Socks5() {}

    static final AtomicInteger active = new AtomicInteger();

    // ponytail: one thread per direction per connection; fine for a few browser clients, use NIO if it ever serves many
    static void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket c = server.accept();
                new Thread(() -> session(c)).start();
            } catch (IOException ignored) {
            }
        }
    }

    private static void session(Socket c) {
        active.incrementAndGet();
        try (Socket client = c) {
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
            if (cmd != 1) {
                reply(out, 7);
                return;
            }
            try (Socket remote = new Socket()) {
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
                Thread up = new Thread(() -> pump(client, remote));
                up.start();
                pump(remote, client);
                up.join();
            }
        } catch (Exception ignored) {
        } finally {
            active.decrementAndGet();
        }
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
        out.write(new byte[] {5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
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

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
