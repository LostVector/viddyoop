package com.rkuo.text;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class UnicodeInputStreamTest {

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] readAll(UnicodeInputStream uis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        int n;
        while ((n = uis.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    @Test
    public void testDetectsUtf8BOMAndSkipsIt() throws IOException {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(concat(bom, body)));
        Assert.assertSame(UnicodeInputStream.BOM.UTF_8, uis.getBOM());

        uis.skipBOM();
        Assert.assertArrayEquals(body, readAll(uis));
    }

    @Test
    public void testNoBOM() throws IOException {
        byte[] body = "plain text".getBytes(StandardCharsets.UTF_8);

        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(body));
        Assert.assertSame(UnicodeInputStream.BOM.NONE, uis.getBOM());

        // skipBOM on a NONE BOM must not consume any data
        uis.skipBOM();
        Assert.assertArrayEquals(body, readAll(uis));
    }

    @Test
    public void testDetectsUtf16LEBOM() throws IOException {
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        byte[] body = "x".getBytes(StandardCharsets.UTF_16LE);

        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(concat(bom, body)));
        Assert.assertSame(UnicodeInputStream.BOM.UTF_16_LE, uis.getBOM());

        uis.skipBOM();
        Assert.assertArrayEquals(body, readAll(uis));
    }

    @Test
    public void testDetectsUtf16BEBOM() throws IOException {
        byte[] bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(concat(bom, body)));
        Assert.assertSame(UnicodeInputStream.BOM.UTF_16_BE, uis.getBOM());
    }

    @Test
    public void testDetectsUtf32LEBOM() throws IOException {
        // FF FE 00 00 must be distinguished from UTF-16 LE (FF FE)
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00};
        byte[] body = "rest".getBytes(StandardCharsets.UTF_8);

        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(concat(bom, body)));
        Assert.assertSame(UnicodeInputStream.BOM.UTF_32_LE, uis.getBOM());

        uis.skipBOM();
        Assert.assertArrayEquals(body, readAll(uis));
    }

    @Test
    public void testEmptyStream() throws IOException {
        UnicodeInputStream uis = new UnicodeInputStream(new ByteArrayInputStream(new byte[0]));
        Assert.assertSame(UnicodeInputStream.BOM.NONE, uis.getBOM());
        Assert.assertEquals(-1, uis.read());
    }

    @Test(expected = NullPointerException.class)
    public void testNullStreamThrows() throws IOException {
        new UnicodeInputStream(null);
    }

    @Test
    public void testBOMMetadata() {
        Assert.assertEquals("UTF-8", UnicodeInputStream.BOM.UTF_8.toString());
        Assert.assertEquals(3, UnicodeInputStream.BOM.UTF_8.getBytes().length);
        Assert.assertEquals("NONE", UnicodeInputStream.BOM.NONE.toString());
        Assert.assertEquals(0, UnicodeInputStream.BOM.NONE.getBytes().length);
    }
}
