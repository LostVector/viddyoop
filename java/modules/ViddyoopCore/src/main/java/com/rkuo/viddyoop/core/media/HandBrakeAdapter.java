package com.rkuo.viddyoop.core.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.handbrake.SubtitlePolicy;
import com.rkuo.handbrake.AudioTrackSelection;
import com.rkuo.handbrake.HBXAudioTrack;
import com.rkuo.handbrake.HBXScanParams;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** HandBrake JSON scan and versioned Apple TV encode settings. */
public final class HandBrakeAdapter {
    public static final String LEGACY_PROFILE = "apple-tv-v1";
    public static final String HEVC_PROFILE = "apple-tv-hevc-v1";
    // Common encoder options recovered from the existing HEVC outputs, also
    // used by HandBrake's Apple 4K preset; audio/selection remain explicit below.
    private static final String HEVC_OPTIONS = "strong-intra-smoothing=0:rect=0:aq-mode=1:rd=4:psy-rd=0.75:"
            + "psy-rdoq=4.0:rdoq-level=1:rskip=2:high-tier=1:vbv-maxrate=60000:vbv-bufsize=60000";
    public record Audio(int track, String codec, int channels, String language, String layout) {
        public Audio(int track, String codec, int channels) {
            this(track, codec, channels, "Unknown", switch (channels) {
                case 1 -> "mono";
                case 2 -> "stereo";
                case 5 -> "5.0";
                case 6 -> "5.1";
                case 8 -> "7.1";
                default -> "";
            });
        }
    }
    public record Video(int bitDepth, int range, int primaries, int transfer, int matrix,
                        int dolbyVisionProfile, boolean hdr10Plus, boolean masteringMetadata) {}
    public record Scan(int title, long durationMs, int width, int height, List<Audio> audio, Video video) {
        public Scan(int title, long durationMs, int width, int height, List<Audio> audio) {
            this(title, durationMs, width, height, audio, new Video(0, 0, 0, 0, 0, 0, false, false));
        }
    }
    private final String executable;
    private final MediaProcessRunner runner;

    public HandBrakeAdapter(String executable, MediaProcessRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    public Scan scan(Path source, Path log, Runnable heartbeat) throws IOException, InterruptedException {
        runner.run(List.of(executable, "--input", source.toString(), "--scan", "--json", "--min-duration", "0"),
                log, Duration.ofMinutes(30), heartbeat);
        return parseScan(log);
    }

    public static Scan parseScan(Path log) throws IOException {
        if (Files.size(log) > 16 * 1024 * 1024) throw new IOException("Scan output exceeds limit: " + log);
        String text = Files.readString(log);
        int marker = text.indexOf("JSON Title Set:");
        int start = marker < 0 ? -1 : text.indexOf('{', marker);
        if (start < 0) throw new IOException("Missing HandBrake JSON title set: " + log);
        JsonNode root = new ObjectMapper().readTree(text.substring(start));
        JsonNode titles = root.path("TitleList");
        // Ingest accepts single-file media. Do not silently choose a title from a disc.
        if (!titles.isArray() || titles.size() != 1) throw new IOException("Expected one media title: " + log);
        JsonNode title = titles.get(0);
        JsonNode duration = title.path("Duration");
        long milliseconds = duration.has("Ticks") ? duration.path("Ticks").asLong() / 90
                : (duration.path("Hours").asLong() * 3600 + duration.path("Minutes").asLong() * 60
                + duration.path("Seconds").asLong()) * 1000;
        int width = title.path("Geometry").path("Width").asInt();
        int height = title.path("Geometry").path("Height").asInt();
        int index = title.path("Index").asInt();
        if (index < 1 || milliseconds <= 0 || width <= 0 || height <= 0) {
            throw new IOException("Invalid media geometry/duration/title: " + log);
        }
        List<Audio> audio = new ArrayList<>();
        for (JsonNode track : title.path("AudioList")) {
            int number = track.path("TrackNumber").asInt();
            int channels = track.path("ChannelCount").asInt();
            if (number < 1 || channels < 1) throw new IOException("Invalid audio track: " + log);
            // Modern HandBrake displays native language names (e.g. 日本語).
            // The shared selector expects the old English labels. Use ISO IDs
            // for the two configured preferences rather than display text.
            String language = switch (track.path("LanguageCode").asText()) {
                case "eng", "en" -> "English";
                case "jpn", "ja" -> "Japanese";
                default -> track.path("Language").asText("Unknown");
            };
            audio.add(new Audio(number, track.path("CodecName").asText(), channels,
                    language, track.path("ChannelLayout").asText("")));
        }
        var color = title.path("Color");
        var mastering = title.path("MasteringDisplayColorVolume");
        return new Scan(index, milliseconds, width, height, List.copyOf(audio),
                new Video(color.path("BitDepth").asInt(), color.path("Range").asInt(),
                        color.path("Primary").asInt(), color.path("Transfer").asInt(), color.path("Matrix").asInt(),
                        title.path("DolbyVisionConfigurationRecord").path("DVProfile").asInt(),
                        title.path("HDR10+").asInt() != 0,
                        // encx265 emits master-display only when both portions
                        // are valid. Partial source objects are not complete HDR
                        // metadata (e.g. reversed min/max clears HasLuminance).
                        mastering.path("HasPrimaries").asBoolean() && mastering.path("HasLuminance").asBoolean()));
    }

    /** Do not publish an apparently successful encode that silently lost HDR or range. */
    public static void validateHevcOutput(Scan source, Scan output) throws IOException {
        Video a = source.video(), b = output.video();
        if (b.bitDepth() != 10) throw new IOException("HEVC output is not 10-bit");
        if ((a.range() > 0 && a.range() != b.range())
                || (a.primaries() > 0 && a.primaries() != b.primaries())
                || (a.transfer() > 0 && a.transfer() != b.transfer())
                || (a.matrix() > 0 && a.matrix() != b.matrix())) {
            throw new IOException("HEVC output color metadata differs from source");
        }
        if (a.dolbyVisionProfile() > 0 && b.dolbyVisionProfile() == 0) {
            throw new IOException("Dolby Vision metadata missing from output; verify HandBrake libdovi support");
        }
        if (a.hdr10Plus() && !b.hdr10Plus()) throw new IOException("HDR10+ metadata missing from output");
        if (a.masteringMetadata() && !b.masteringMetadata()) throw new IOException("HDR mastering metadata missing from output");
    }

    public List<String> encodeCommand(Path source, Path output, Scan scan, boolean transportStream) {
        return planCommand(source, output, scan, transportStream, LEGACY_PROFILE).arguments();
    }

    public static boolean supportsProfile(String profile) {
        return LEGACY_PROFILE.equals(profile) || HEVC_PROFILE.equals(profile);
    }

    private record CommandPlan(List<String> arguments, int audioTracks) {}

    /** Bridge the JSON scan to the original selector without duplicating its precedence. */
    public static List<Audio> selectedAudio(Scan scan) {
        if (scan.audio().isEmpty()) throw new IllegalArgumentException("Source has no audio tracks");
        var legacy = new HBXScanParams();
        for (Audio audio : scan.audio()) {
            var track = new HBXAudioTrack();
            track.TrackNumber = audio.track();
            track.Language = audio.language();
            track.Codec = audio.codec();
            track.SurroundNotation = audio.layout();
            legacy.AudioTracks.add(track);
        }
        // Preferences from the existing movie configuration. Keep them part of
        // the versioned profile so retries do not depend on worker environment.
        return AudioTrackSelection.select("English", "Japanese", legacy).stream()
                .map(selected -> scan.audio().stream().filter(a -> a.track() == selected.TrackNumber)
                        .findFirst().orElseThrow()).toList();
    }

    private CommandPlan planCommand(Path source, Path output, Scan scan, boolean transportStream, String profile) {
        if (!supportsProfile(profile)) throw new IllegalArgumentException("Unsupported encode profile: " + profile);
        boolean hevc = HEVC_PROFILE.equals(profile);
        List<Audio> selectedAudio = hevc ? selectedAudio(scan) : scan.audio();
        boolean fullHd = scan.width() > 1280 || scan.height() > 720;
        List<String> args = new ArrayList<>(List.of(executable, "--input", source.toString(),
                "--output", output.toString(), "--title", Integer.toString(scan.title()),
                "--format", "av_mp4", "--encoder", hevc ? "x265_10bit" : "x264", "--quality", fullHd ? "22" : "20",
                "--encopts", hevc ? HEVC_OPTIONS
                        : fullHd ? "b-adapt=2:rc-lookahead=50:vbv-maxrate=25000:vbv-bufsize=20000"
                        : "b-adapt=2:rc-lookahead=50:vbv-maxrate=14000:vbv-bufsize=14000",
                "--rate", "29.97", "--pfr", "--crop", "0:0:0:0", "--maxWidth", hevc ? "3840" : "1920",
                "--maxHeight", hevc ? "2160" : "1080", "--auto-anamorphic", "--markers"));
        if (hevc) args.addAll(List.of("--encoder-profile", "main10", "--encoder-level", "5.0",
                "--encoder-preset", "slow", "--color-range", "auto"));
        List<String> tracks = new ArrayList<>(), encoders = new ArrayList<>(), mixdowns = new ArrayList<>();
        List<String> bitrates = new ArrayList<>(), drc = new ArrayList<>(), rates = new ArrayList<>();
        for (Audio audio : selectedAudio) {
            tracks.add(Integer.toString(audio.track())); encoders.add("av_aac");
            mixdowns.add("dpl2"); bitrates.add("224"); drc.add("2.0"); rates.add("48");
        }
        for (Audio audio : selectedAudio) {
            if (audio.channels() <= 2) continue;
            tracks.add(Integer.toString(audio.track()));
            String codec = audio.codec().toLowerCase(Locale.ROOT);
            boolean passthrough = codec.equals("ac3") || codec.equals("eac3");
            encoders.add(passthrough ? "copy:" + codec : "ac3");
            mixdowns.add(passthrough ? "auto" : "5point1");
            bitrates.add(passthrough ? "auto" : "448");
            rates.add(passthrough ? "auto" : "48");
            drc.add("0.0");
        }
        if (tracks.isEmpty()) args.addAll(List.of("--audio", "none"));
        else args.addAll(List.of("--audio", String.join(",", tracks), "--aencoder", String.join(",", encoders),
                "--mixdown", String.join(",", mixdowns), "--ab", String.join(",", bitrates),
                "--arate", String.join(",", rates), "--drc", String.join(",", drc)));
        if (transportStream) args.addAll(List.of("--decomb", "--detelecine"));
        return new CommandPlan(List.copyOf(args), tracks.size());
    }

    public void encode(Path source, Path output, Scan scan, boolean transportStream, Path log, Runnable heartbeat)
            throws IOException, InterruptedException {
        encode(source, output, scan, transportStream, log, heartbeat, List.of());
    }

    public void encode(Path source, Path output, Scan scan, boolean transportStream, Path log, Runnable heartbeat,
                       List<com.rkuo.viddyoop.core.pipeline.PreprocessResult.Subtitle> subtitles)
            throws IOException, InterruptedException {
        encode(source, output, scan, transportStream, log, heartbeat, subtitles, LEGACY_PROFILE);
    }

    public void encode(Path source, Path output, Scan scan, boolean transportStream, Path log, Runnable heartbeat,
                       List<com.rkuo.viddyoop.core.pipeline.PreprocessResult.Subtitle> subtitles, String profile)
            throws IOException, InterruptedException {
        runner.run(encodeCommand(source, output, scan, transportStream, subtitles, profile), log, Duration.ofHours(24), heartbeat);
    }

    public List<String> encodeCommand(Path source, Path output, Scan scan, boolean transportStream,
                       List<com.rkuo.viddyoop.core.pipeline.PreprocessResult.Subtitle> subtitles) throws IOException {
        return encodeCommand(source, output, scan, transportStream, subtitles, LEGACY_PROFILE);
    }

    public List<String> encodeCommand(Path source, Path output, Scan scan, boolean transportStream,
                       List<com.rkuo.viddyoop.core.pipeline.PreprocessResult.Subtitle> subtitles, String profile) throws IOException {
        CommandPlan plan = planCommand(source, output, scan, transportStream, profile);
        List<String> command = new ArrayList<>(plan.arguments());
        var selected = SubtitlePolicy.select(subtitles, plan.audioTracks(), subtitle -> subtitle.language);
        if (selected.size() < subtitles.size()) {
            org.slf4j.LoggerFactory.getLogger(HandBrakeAdapter.class).warn(
                    "Keeping {} of {} subtitles under the {} total-track limit",
                    selected.size(), subtitles.size(), SubtitlePolicy.MAX_OUTPUT_TRACKS);
        }
        if (!selected.isEmpty()) {
            for (var subtitle : selected) {
                if (subtitle.path.contains(",")) throw new IOException("Subtitle paths cannot contain commas");
            }
            command.addAll(List.of("--srt-file", String.join(",", selected.stream().map(s -> s.path).toList()),
                    "--srt-codeset", String.join(",", Collections.nCopies(selected.size(), "UTF-8")),
                    "--srt-lang", String.join(",", selected.stream().map(s -> SubtitlePolicy.languageCode(s.language)).toList())));
            for (int i = 0; i < selected.size(); i++) {
                if (selected.get(i).defaultTrack) {
                    // getopt optional arguments require '='; a separate value
                    // is ignored and would mark the first SRT as default.
                    command.add("--srt-default=" + (i + 1));
                    break;
                }
            }
        }
        return List.copyOf(command);
    }
}
