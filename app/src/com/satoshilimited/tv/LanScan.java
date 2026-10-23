package com.satoshilimited.tv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class LanScan {
    private LanScan() {}

    interface Probe<T> { T at(String host); }

    static String prefix() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements(); ) {
                NetworkInterface nif = e.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String host = a.getHostAddress();
                    return host.substring(0, host.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static <T> List<T> run(String prefix, Probe<T> probe) {
        List<T> found = Collections.synchronizedList(new ArrayList<>());
        if (prefix.isEmpty()) return new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        AtomicInteger left = new AtomicInteger(254);
        Object done = new Object();
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                T hit = probe.at(host);
                if (hit != null) found.add(hit);
                if (left.decrementAndGet() == 0) synchronized (done) { done.notifyAll(); }
            });
        }
        synchronized (done) {
            long until = System.currentTimeMillis() + 15_000;
            while (left.get() > 0 && System.currentTimeMillis() < until) {
                try {
                    done.wait(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        pool.shutdownNow();
        return new ArrayList<>(found);
    }
}
