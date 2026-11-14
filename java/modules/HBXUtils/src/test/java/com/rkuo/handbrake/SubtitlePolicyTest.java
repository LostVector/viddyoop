package com.rkuo.handbrake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlePolicyTest {
    @Test public void keepsAllLanguagesWhenTheyFitAndOrdersPriorityGroupsStably() {
        List<String> input = Arrays.asList("ger", "fre", "spa", "eng", "fra", "jpn", "und");
        assertEquals(Arrays.asList("eng", "spa", "fre", "fra", "ger", "jpn", "und"),
                SubtitlePolicy.select(input, 2, language -> language));
        assertEquals(Arrays.asList("ger", "fre", "spa", "eng", "fra", "jpn", "und"), input);
    }

    @Test public void priorityAppliesBeforeTheLegacyTotalTrackCutoff() {
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 30; i++) input.add("jpn-" + i);
        input.addAll(Arrays.asList("fre", "spa", "eng"));
        List<String> selected = SubtitlePolicy.select(input, 2, language -> language);
        assertEquals(28, selected.size());
        assertEquals(Arrays.asList("eng", "spa", "fre"), selected.subList(0, 3));
        assertEquals("jpn-0", selected.get(3));
        assertEquals("jpn-24", selected.get(27));
    }

    @Test public void countsVideoAndEveryOutputAudioTrackInBudget() {
        assertEquals(30, SubtitlePolicy.subtitleSlots(0));
        assertEquals(28, SubtitlePolicy.subtitleSlots(2));
        assertEquals(26, SubtitlePolicy.subtitleSlots(4));
        assertEquals(0, SubtitlePolicy.subtitleSlots(30));
        assertEquals(0, SubtitlePolicy.subtitleSlots(31));
        assertThrows(IllegalArgumentException.class, () -> SubtitlePolicy.subtitleSlots(-1));
    }

    @Test public void reusesLegacyLanguageAliasesWithoutChangingUnknownCodes() {
        assertEquals("fra", SubtitlePolicy.languageCode("fre"));
        assertEquals("deu", SubtitlePolicy.languageCode("ger"));
        assertEquals("zho", SubtitlePolicy.languageCode("chi"));
        assertEquals("jpn", SubtitlePolicy.languageCode("jpn"));
        assertEquals("und", SubtitlePolicy.languageCode("und"));
        assertEquals(2, SubtitlePolicy.priority("FRE"));
        assertEquals(3, SubtitlePolicy.priority(null));
    }
}
