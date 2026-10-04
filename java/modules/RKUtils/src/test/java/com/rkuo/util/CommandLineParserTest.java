package com.rkuo.util;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CommandLineParserTest {

    @Test
    public void testParseBasicArgs() {
        CommandLineParser clp = new CommandLineParser();
        Assert.assertTrue(clp.Parse(new String[]{"/Verbose:true", "/JobName:My Job", "/Count:42"}));

        Assert.assertTrue(clp.Contains("verbose"));
        Assert.assertTrue(clp.Contains("jobname"));
        Assert.assertTrue(clp.Contains("count"));
        Assert.assertFalse(clp.Contains("nonexistent"));
    }

    @Test
    public void testKeysAreCaseInsensitive() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/Verbose:true"});

        Assert.assertTrue(clp.Contains("VERBOSE"));
        Assert.assertTrue(clp.Contains("Verbose"));
        Assert.assertTrue(clp.GetBoolean("VERBOSE"));
    }

    @Test
    public void testInvalidArgsAreSkipped() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{
                "noslash:true",       // must start with slash
                "/nocolon",           // must have a colon
                "/:emptykey",         // key cannot be empty
                "/ok:yes"             // the only valid one
        });

        Assert.assertFalse(clp.Contains("noslash"));
        Assert.assertFalse(clp.Contains("nocolon"));
        Assert.assertFalse(clp.Contains(""));
        Assert.assertTrue(clp.Contains("ok"));
    }

    @Test
    public void testValueMayContainColons() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/url:http://example.com:8080/path"});

        Assert.assertEquals("http://example.com:8080/path", clp.GetString("url"));
    }

    @Test
    public void testGetBoolean() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/yes:true", "/no:false", "/maybe:yes", "/flag:TRUE"});

        Assert.assertTrue(clp.GetBoolean("yes"));
        // the value comparison is case-insensitive
        Assert.assertTrue(clp.GetBoolean("flag"));
        Assert.assertFalse(clp.GetBoolean("no"));
        // only the literal string "true" is truthy
        Assert.assertFalse(clp.GetBoolean("maybe"));
        // missing key defaults to false
        Assert.assertFalse(clp.GetBoolean("missing"));
    }

    @Test
    public void testGetIntegerAndLong() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/count:42", "/bignum:8589934592"});

        Assert.assertEquals(42, clp.GetInteger("count"));
        Assert.assertEquals(8589934592L, clp.GetLong("bignum"));

        // missing keys default to 0
        Assert.assertEquals(0, clp.GetInteger("missing"));
        Assert.assertEquals(0L, clp.GetLong("missing"));
    }

    @Test(expected = NumberFormatException.class)
    public void testGetIntegerOnNonNumericValueThrows() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/count:abc"});
        clp.GetInteger("count");
    }

    @Test
    public void testGetString() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/name:viddyoop"});

        Assert.assertEquals("viddyoop", clp.GetString("name"));
        // missing key defaults to empty string
        Assert.assertEquals("", clp.GetString("missing"));
    }

    @Test
    public void testLaterValueWins() {
        CommandLineParser clp = new CommandLineParser();
        clp.Parse(new String[]{"/name:first", "/name:second"});

        Assert.assertEquals("second", clp.GetString("name"));
    }
}
