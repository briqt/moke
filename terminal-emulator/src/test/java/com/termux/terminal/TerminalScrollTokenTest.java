package com.termux.terminal;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TerminalScrollTokenTest extends TestCase {

    public void testCancellationIsPermanentAndIndependent() {
        TerminalScrollToken first = new TerminalScrollToken();
        TerminalScrollToken second = new TerminalScrollToken();
        assertTrue(first.isValid());
        first.cancel();
        first.cancel();
        assertFalse(first.isValid());
        assertTrue(second.isValid());
    }

    public void testCancellationIsVisibleToConcurrentReaders() throws Exception {
        TerminalScrollToken token = new TerminalScrollToken();
        int readers = 8;
        CountDownLatch ready = new CountDownLatch(readers);
        CountDownLatch done = new CountDownLatch(readers);
        AtomicInteger observed = new AtomicInteger();
        for (int i = 0; i < readers; i++) {
            Thread reader = new Thread(() -> {
                ready.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (token.isValid() && System.nanoTime() < deadline) Thread.yield();
                if (!token.isValid()) observed.incrementAndGet();
                done.countDown();
            }, "scroll-token-reader");
            reader.setDaemon(true);
            reader.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        token.cancel();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(readers, observed.get());
    }

    public void testDefaultTransportGatesCanceledScrollWithoutChangingOrdinaryWrites() {
        class Transport implements TerminalTransport {
            byte[] bytes;
            int writes;

            @Override public void start(TerminalSession session, int columns, int rows, int width, int height) {}
            @Override public void write(byte[] data, int offset, int count) {
                bytes = Arrays.copyOfRange(data, offset, offset + count);
                writes++;
            }
            @Override public void updateSize(int columns, int rows, int width, int height) {}
            @Override public void close() {}
        }
        Transport transport = new Transport();
        byte[] encoded = new byte[] {0, 27, '[', 'A', 0};
        TerminalScrollToken token = new TerminalScrollToken();
        transport.writeScroll(encoded, 1, 3, token);
        assertTrue(Arrays.equals(new byte[] {27, '[', 'A'}, transport.bytes));
        assertEquals(1, transport.writes);
        token.cancel();
        transport.writeScroll(encoded, 1, 3, token);
        assertEquals(1, transport.writes);
        transport.write(encoded, 1, 3);
        assertEquals(2, transport.writes);
    }
}
