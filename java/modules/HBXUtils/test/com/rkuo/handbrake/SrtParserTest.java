package com.rkuo.handbrake;

import com.rkuo.subtitles.SrtEntry;
import com.rkuo.subtitles.SrtParser;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public class SrtParserTest {

    @Test
    public void testRepairSrt() {
        String source = "/Users/root/Downloads/hbxtest/blank_line_leading.srt";
        String target = "/Users/root/Downloads/hbxtest/blank_line_leading_repaired.srt";

//        String source = "/Users/root/Downloads/hbxtest/blank_line_interspersed.srt";
//        String target = "/Users/root/Downloads/hbxtest/blank_line_interspersed_repaired.srt";

//        String source = "/Users/root/Downloads/hbxtest/3_eng_ubuntu.srt";
//        String target = "/Users/root/Downloads/hbxtest/3_eng_ubuntu_repaired.srt";

        List<SrtEntry> entries = SrtParser.parseFileStrict(source);
        if( entries == null ) {
            entries = SrtParser.parseFileLoose(source);
            if( entries != null ) {
                boolean br = SrtParser.writeFile(target,entries);
                Assert.assertTrue(br);
            }
        }
    }
}