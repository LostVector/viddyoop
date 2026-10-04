package com.rkuo.handbrake;

import com.rkuo.subtitles.SrtEntry;
import com.rkuo.subtitles.SrtParser;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class SrtParserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File copyFixtureToTemp(String resourceName) throws IOException {
        File target = tempFolder.newFile(resourceName);
        InputStream is = getClass().getResourceAsStream("/" + resourceName);
        Assert.assertNotNull("missing test fixture: " + resourceName, is);
        try {
            OutputStream os = new FileOutputStream(target);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            finally {
                os.close();
            }
        }
        finally {
            is.close();
        }
        return target;
    }

    @Test
    public void testParseValidStrict() throws Exception {
        File f = copyFixtureToTemp("srt_valid.srt");

        List<SrtEntry> entries = SrtParser.parseFileStrict(f.getAbsolutePath());
        Assert.assertNotNull(entries);
        Assert.assertEquals(3, entries.size());

        SrtEntry e = entries.get(0);
        Assert.assertEquals(Long.valueOf(1), e.Index);
        Assert.assertEquals(Long.valueOf(1000), e.Start);
        Assert.assertEquals(Long.valueOf(4000), e.End);
        Assert.assertEquals("Hello, world.\n", e.Text);

        e = entries.get(1);
        Assert.assertEquals(Long.valueOf(2), e.Index);
        Assert.assertEquals(Long.valueOf(5500), e.Start);
        Assert.assertEquals(Long.valueOf(8250), e.End);
        Assert.assertEquals("Second subtitle line.\nMulti-line text here.\n", e.Text);

        e = entries.get(2);
        Assert.assertEquals(Long.valueOf(3), e.Index);
        Assert.assertEquals(Long.valueOf(1 * 3600000 + 2 * 60000 + 3 * 1000 + 4), e.Start);
        Assert.assertEquals(Long.valueOf(1 * 3600000 + 2 * 60000 + 5 * 1000), e.End);
        Assert.assertEquals("Third entry with <i>formatting</i>.\n", e.Text);
    }

    @Test
    public void testStrictRejectsLeadingBlankLines() throws Exception {
        File f = copyFixtureToTemp("srt_leading_blank_lines.srt");

        List<SrtEntry> entries = SrtParser.parseFileStrict(f.getAbsolutePath());
        Assert.assertNull(entries);
    }

    @Test
    public void testLooseRecoversLeadingBlankLines() throws Exception {
        File f = copyFixtureToTemp("srt_leading_blank_lines.srt");

        List<SrtEntry> entries = SrtParser.parseFileLoose(f.getAbsolutePath());
        Assert.assertNotNull(entries);
        Assert.assertEquals(2, entries.size());

        SrtEntry e = entries.get(0);
        Assert.assertEquals(Long.valueOf(1), e.Index);
        Assert.assertEquals(Long.valueOf(1000), e.Start);
        Assert.assertEquals(Long.valueOf(4000), e.End);
        Assert.assertEquals("Hello, world.\n", e.Text);

        e = entries.get(1);
        Assert.assertEquals(Long.valueOf(2), e.Index);
        Assert.assertEquals(Long.valueOf(5000), e.Start);
        Assert.assertEquals(Long.valueOf(8000), e.End);
        Assert.assertEquals("Second entry after a leading blank line.\n", e.Text);
    }

    @Test
    public void testBadSequenceStrictRejectsLooseRecovers() throws Exception {
        File f = copyFixtureToTemp("srt_bad_sequence.srt");

        // strict parser rejects the out-of-order sequence number
        Assert.assertNull(SrtParser.parseFileStrict(f.getAbsolutePath()));

        // loose parser swallows the mismatched sequence number (and everything after
        // it) as text of the first entry instead of failing
        List<SrtEntry> entries = SrtParser.parseFileLoose(f.getAbsolutePath());
        Assert.assertNotNull(entries);
        Assert.assertEquals(1, entries.size());
        Assert.assertEquals(Long.valueOf(1), entries.get(0).Index);
        Assert.assertTrue(entries.get(0).Text.contains("First entry."));
        Assert.assertTrue(entries.get(0).Text.contains("3"));
    }

    @Test
    public void testMissingFileReturnsNull() {
        String missing = new File(tempFolder.getRoot(), "does_not_exist.srt").getAbsolutePath();

        Assert.assertNull(SrtParser.parseFileStrict(missing));
        Assert.assertNull(SrtParser.parseFileLoose(missing));
    }

    @Test
    public void testWriteRoundTrip() throws Exception {
        File f = copyFixtureToTemp("srt_valid.srt");

        List<SrtEntry> entries = SrtParser.parseFileStrict(f.getAbsolutePath());
        Assert.assertNotNull(entries);

        File out = new File(tempFolder.getRoot(), "roundtrip.srt");
        Assert.assertTrue(SrtParser.writeFile(out.getAbsolutePath(), entries));

        List<SrtEntry> reparsed = SrtParser.parseFileStrict(out.getAbsolutePath());
        Assert.assertNotNull(reparsed);
        Assert.assertEquals(entries.size(), reparsed.size());
        for (int i = 0; i < entries.size(); i++) {
            Assert.assertEquals(entries.get(i).Index, reparsed.get(i).Index);
            Assert.assertEquals(entries.get(i).Start, reparsed.get(i).Start);
            Assert.assertEquals(entries.get(i).End, reparsed.get(i).End);
            Assert.assertEquals(entries.get(i).Text, reparsed.get(i).Text);
        }
    }

    @Test
    public void testWriteMissingPathReturnsFalse() {
        List<SrtEntry> entries = new ArrayList<SrtEntry>();

        SrtEntry e = new SrtEntry();
        e.Index = 1L;
        e.Start = 0L;
        e.End = 1000L;
        e.Text = "text\n";
        entries.add(e);

        String badPath = new File(tempFolder.getRoot(), "no/such/dir/out.srt").getAbsolutePath();
        Assert.assertFalse(SrtParser.writeFile(badPath, entries));
    }

    @Test
    public void testHasFormatting() {
        List<SrtEntry> plain = new ArrayList<SrtEntry>();
        SrtEntry e = new SrtEntry();
        e.Text = "no formatting here";
        plain.add(e);
        Assert.assertFalse(SrtEntry.HasFormatting(plain));

        List<SrtEntry> italic = new ArrayList<SrtEntry>();
        e = new SrtEntry();
        e.Text = "some <i>italic</i> text";
        italic.add(e);
        Assert.assertTrue(SrtEntry.HasFormatting(italic));

        List<SrtEntry> bold = new ArrayList<SrtEntry>();
        e = new SrtEntry();
        e.Text = "some <b>bold</b> text";
        bold.add(e);
        Assert.assertTrue(SrtEntry.HasFormatting(bold));

        List<SrtEntry> font = new ArrayList<SrtEntry>();
        e = new SrtEntry();
        e.Text = "colored</font> text";
        font.add(e);
        Assert.assertTrue(SrtEntry.HasFormatting(font));
    }
}
