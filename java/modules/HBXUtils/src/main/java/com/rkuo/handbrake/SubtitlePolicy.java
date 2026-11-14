package com.rkuo.handbrake;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Legacy total-track budget and language mapping, with operator-requested subtitle priority. */
public final class SubtitlePolicy {
    /** Existing wrapper limit: one video plus all output audio and subtitle tracks. */
    public static final int MAX_OUTPUT_TRACKS = 31;

    private SubtitlePolicy() {}

    public static int subtitleSlots(int outputAudioTracks) {
        if (outputAudioTracks < 0) throw new IllegalArgumentException("Negative audio track count");
        return Math.max(0, MAX_OUTPUT_TRACKS - 1 - outputAudioTracks);
    }

    /** Stable within each priority group; all remaining languages retain input order. */
    public static <T> List<T> select(List<T> tracks, int outputAudioTracks, Function<T, String> language) {
        List<T> ordered = new ArrayList<T>(tracks);
        ordered.sort(Comparator.comparingInt(track -> priority(language.apply(track))));
        return List.copyOf(ordered.subList(0, Math.min(ordered.size(), subtitleSlots(outputAudioTracks))));
    }

    public static int priority(String language) {
        if (language == null) return 3;
        return switch (languageCode(language).toLowerCase(Locale.ROOT)) {
            case "eng", "en" -> 0;
            case "spa", "es" -> 1;
            case "fra", "fr" -> 2;
            default -> 3;
        };
    }

    public static String languageCode(String code) {

        String outCode;

        if( code.compareToIgnoreCase("alb") == 0 ) {
            outCode = "sqi";
        }
        else if( code.compareToIgnoreCase("arm") == 0 ) {
            outCode = "hye";
        }
        else if( code.compareToIgnoreCase("baq") == 0 ) {
            outCode = "eus";
        }
        else if( code.compareToIgnoreCase("bur") == 0 ) {
            outCode = "mya";
        }
        else if( code.compareToIgnoreCase("chi") == 0 ) {
            outCode = "zho";
        }
        else if( code.compareToIgnoreCase("cze") == 0 ) {
            outCode = "ces";
        }
        else if( code.compareToIgnoreCase("dut") == 0 ) {
            outCode = "nld";
        }
        else if( code.compareToIgnoreCase("fre") == 0 ) {
            outCode = "fra";
        }
        else if( code.compareToIgnoreCase("geo") == 0 ) {
            outCode = "kat";
        }
        else if( code.compareToIgnoreCase("ger") == 0 ) {
            outCode = "deu";
        }
        else if( code.compareToIgnoreCase("gre") == 0 ) {
            outCode = "ell";
        }
        else if( code.compareToIgnoreCase("ice") == 0 ) {
            outCode = "isl";
        }
        else if( code.compareToIgnoreCase("mac") == 0 ) {
            outCode = "mkd";
        }
        else if( code.compareToIgnoreCase("may") == 0 ) {
            outCode = "msa";
        }
        else if( code.compareToIgnoreCase("per") == 0 ) {
            outCode = "fas";
        }
        else if( code.compareToIgnoreCase("rum") == 0 ) {
            outCode = "ron";
        }
        else if( code.compareToIgnoreCase("slo") == 0 ) {
            outCode = "slk";
        }
        else if( code.compareToIgnoreCase("tib") == 0 ) {
            outCode = "bod";
        }
        else if( code.compareToIgnoreCase("wel") == 0 ) {
            outCode = "cym";
        }
        else {
            outCode = code;
        }

        return outCode;
    }

}
