package com.rkuo.util;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

@RunWith(JUnit4.class)
public class Base64Test {

    @Test
    public void testEncodeKnownVector() throws IOException {
        String encoded = Base64.encodeBytes("Hello World".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals("SGVsbG8gV29ybGQ=", encoded);
    }

    @Test
    public void testDecodeKnownVector() throws IOException {
        byte[] decoded = Base64.decode("SGVsbG8gV29ybGQ=");
        Assert.assertEquals("Hello World", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    public void testPaddingVariants() throws IOException {
        // 1, 2, 3 byte inputs exercise all padding cases
        Assert.assertEquals("YQ==", Base64.encodeBytes("a".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("YWI=", Base64.encodeBytes("ab".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("YWJj", Base64.encodeBytes("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testRoundTripAllLengths() throws IOException {
        Random random = new Random(42);

        for (int len = 0; len <= 64; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);

            String encoded = Base64.encodeBytes(data);
            byte[] decoded = Base64.decode(encoded);
            Assert.assertArrayEquals("round trip failed for length " + len, data, decoded);
        }
    }

    @Test
    public void testRoundTripWithBreakLines() throws IOException {
        byte[] data = new byte[1000];
        new Random(7).nextBytes(data);

        String encoded = Base64.encodeBytes(data, Base64.DO_BREAK_LINES);
        Assert.assertTrue(encoded.contains("\n"));

        byte[] decoded = Base64.decode(encoded, Base64.DO_BREAK_LINES);
        Assert.assertArrayEquals(data, decoded);
    }

    @Test
    public void testRoundTripWithGzip() throws IOException {
        byte[] data = new byte[5000];
        Arrays.fill(data, (byte) 'x'); // very compressible

        String encoded = Base64.encodeBytes(data, Base64.GZIP);
        byte[] decoded = Base64.decode(encoded, Base64.GZIP);
        Assert.assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeBytesToBytesRoundTrip() throws IOException {
        byte[] data = "some bytes".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = Base64.encodeBytesToBytes(data);
        byte[] decoded = Base64.decode(encoded);
        Assert.assertArrayEquals(data, decoded);
    }

    @Test
    public void testEncodeDecodeObject() throws IOException, ClassNotFoundException {
        String original = "serializable payload";

        String encoded = Base64.encodeObject(original);
        Object decoded = Base64.decodeToObject(encoded);
        Assert.assertEquals(original, decoded);
    }

    @Test
    public void testDecodeEmptyString() throws IOException {
        byte[] decoded = Base64.decode("");
        Assert.assertNotNull(decoded);
        Assert.assertEquals(0, decoded.length);
    }
}
