package com.rkuo.io;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class CircularByteBufferTest {

    @Test
    public void testWriteThenRead() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(1024);
        OutputStream out = cbb.getOutputStream();
        InputStream in = cbb.getInputStream();

        out.write("hello".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(5, cbb.getAvailable());
        // usable capacity is getSize() - 1, so 1024 - 1 - 5 bytes written
        Assert.assertEquals(1018, cbb.getSpaceLeft());
        Assert.assertEquals(1024, cbb.getSize());

        byte[] buf = new byte[5];
        Assert.assertEquals(5, in.read(buf));
        Assert.assertEquals("hello", new String(buf, StandardCharsets.UTF_8));
        Assert.assertEquals(0, cbb.getAvailable());
    }

    @Test
    public void testWrapAroundPreservesOrder() throws Exception {
        // getSize() reports the backing array length; usable capacity is one less
        CircularByteBuffer cbb = new CircularByteBuffer(8);
        Assert.assertEquals(8, cbb.getSize());
        Assert.assertEquals(7, cbb.getSpaceLeft());
        OutputStream out = cbb.getOutputStream();
        InputStream in = cbb.getInputStream();

        // write 1..5, read 3 to advance the read position, then write 6..8 which
        // must wrap around the end of the backing array
        out.write(new byte[]{1, 2, 3, 4, 5});
        byte[] first = new byte[3];
        Assert.assertEquals(3, in.read(first));
        Assert.assertArrayEquals(new byte[]{1, 2, 3}, first);

        out.write(new byte[]{6, 7, 8});
        Assert.assertEquals(5, cbb.getAvailable());

        byte[] rest = new byte[5];
        Assert.assertEquals(5, in.read(rest));
        Assert.assertArrayEquals(new byte[]{4, 5, 6, 7, 8}, rest);
    }

    @Test
    public void testSingleByteReadAndWrite() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(16);
        OutputStream out = cbb.getOutputStream();
        InputStream in = cbb.getInputStream();

        out.write('A');
        out.write('B');
        Assert.assertEquals('A', in.read());
        Assert.assertEquals('B', in.read());
        Assert.assertEquals(0, cbb.getAvailable());
    }

    @Test
    public void testNonBlockingWriteOnFullBufferThrows() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(4, false); // capacity 3
        OutputStream out = cbb.getOutputStream();

        out.write(new byte[]{1, 2, 3});
        Assert.assertEquals(0, cbb.getSpaceLeft());

        try {
            out.write(new byte[]{4});
            Assert.fail("expected BufferOverflowException on full non-blocking buffer");
        }
        catch (BufferOverflowException expected) {
            // ok
        }
    }

    @Test
    public void testInfiniteBufferGrows() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(CircularByteBuffer.INFINITE_SIZE);
        OutputStream out = cbb.getOutputStream();
        InputStream in = cbb.getInputStream();

        byte[] payload = new byte[10000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        out.write(payload);

        Assert.assertEquals(payload.length, cbb.getAvailable());

        byte[] readBack = new byte[payload.length];
        int off = 0;
        while (off < payload.length) {
            int n = in.read(readBack, off, payload.length - off);
            Assert.assertTrue(n > 0);
            off += n;
        }
        Assert.assertArrayEquals(payload, readBack);
    }

    @Test
    public void testBlockingWriteUnblocksWhenReaderDrains() throws Exception {
        final CircularByteBuffer cbb = new CircularByteBuffer(4); // capacity 3, blocking writes
        final byte[] payload = {10, 20, 30, 40, 50};
        final byte[] received = new byte[payload.length];
        final Exception[] readerError = new Exception[1];

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = cbb.getInputStream();
                    int off = 0;
                    while (off < payload.length) {
                        int n = in.read(received, off, payload.length - off);
                        if (n < 0) {
                            break;
                        }
                        off += n;
                    }
                }
                catch (Exception ex) {
                    readerError[0] = ex;
                }
            }
        });
        reader.start();

        // this write exceeds capacity and must block until the reader drains
        cbb.getOutputStream().write(payload);
        reader.join(10000);

        Assert.assertNull(readerError[0]);
        Assert.assertArrayEquals(payload, received);
    }

    @Test
    public void testClearResetsBuffer() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(16);
        cbb.getOutputStream().write("data".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(4, cbb.getAvailable());

        cbb.clear();
        Assert.assertEquals(0, cbb.getAvailable());
        Assert.assertEquals(cbb.getSize() - 1, cbb.getSpaceLeft());
    }

    @Test
    public void testWriteAfterOutputStreamCloseThrows() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(16);
        cbb.getOutputStream().close();

        try {
            cbb.getOutputStream().write(new byte[]{1});
            Assert.fail("expected IOException writing to closed OutputStream");
        }
        catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void testReadReturnsEofAfterOutputStreamClosed() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(16);
        cbb.getOutputStream().write("x".getBytes(StandardCharsets.UTF_8));
        cbb.getOutputStream().close();

        InputStream in = cbb.getInputStream();
        Assert.assertEquals('x', in.read());
        Assert.assertEquals(-1, in.read());
    }

    @Test
    public void testSkip() throws Exception {
        CircularByteBuffer cbb = new CircularByteBuffer(16);
        cbb.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));

        InputStream in = cbb.getInputStream();
        Assert.assertEquals(3, in.skip(3));
        Assert.assertEquals('d', in.read());
    }
}
