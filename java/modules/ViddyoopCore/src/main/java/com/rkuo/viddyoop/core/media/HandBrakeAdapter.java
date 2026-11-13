package com.rkuo.viddyoop.core.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** HandBrake 1.x JSON scan and explicit legacy Apple TV encode settings. */
public final class HandBrakeAdapter {
    public record Audio(int track, String codec, int channels) {}
    public record Scan(int title, long durationMs, int width, int height, List<Audio> audio) {}
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
            audio.add(new Audio(number, track.path("CodecName").asText(), channels));
        }
        return new Scan(index, milliseconds, width, height, List.copyOf(audio));
    }

    public List<String> encodeCommand(Path source, Path output, Scan scan, boolean transportStream) {
        boolean fullHd = scan.width() > 1280 || scan.height() > 720;
        List<String> args = new ArrayList<>(List.of(executable, "--input", source.toString(),
                "--output", output.toString(), "--title", Integer.toString(scan.title()),
                "--format", "av_mp4", "--encoder", "x264", "--quality", fullHd ? "22" : "20",
                "--encopts", fullHd ? "b-adapt=2:rc-lookahead=50:vbv-maxrate=25000:vbv-bufsize=20000"
                        : "b-adapt=2:rc-lookahead=50:vbv-maxrate=14000:vbv-bufsize=14000",
                "--rate", "29.97", "--pfr", "--crop", "0:0:0:0", "--maxWidth", "1920",
                "--maxHeight", "1080", "--auto-anamorphic", "--markers"));
        List<String> tracks = new ArrayList<>(), encoders = new ArrayList<>(), mixdowns = new ArrayList<>();
        List<String> bitrates = new ArrayList<>(), drc = new ArrayList<>();
        for (Audio audio : scan.audio()) {
            tracks.add(Integer.toString(audio.track())); encoders.add("av_aac");
            mixdowns.add("dpl2"); bitrates.add("224"); drc.add("2.0");
        }
        for (Audio audio : scan.audio()) {
            if (audio.channels() <= 2) continue;
            tracks.add(Integer.toString(audio.track()));
            encoders.add(audio.codec().equalsIgnoreCase("ac3") ? "copy:ac3" : "ac3");
            mixdowns.add("5point1"); bitrates.add("448"); drc.add("0.0");
        }
        if (tracks.isEmpty()) args.addAll(List.of("--audio", "none"));
        else args.addAll(List.of("--audio", String.join(",", tracks), "--aencoder", String.join(",", encoders),
                "--mixdown", String.join(",", mixdowns), "--ab", String.join(",", bitrates),
                "--arate", "48", "--drc", String.join(",", drc)));
        if (transportStream) args.addAll(List.of("--decomb", "--detelecine"));
        return List.copyOf(args);
    }

    public void encode(Path source, Path output, Scan scan, boolean transportStream, Path log, Runnable heartbeat)
            throws IOException, InterruptedException {
        encode(source, output, scan, transportStream, log, heartbeat, List.of());
    }

    public void encode(Path source, Path output, Scan scan, boolean transportStream, Path log, Runnable heartbeat,
                       List<com.rkuo.viddyoop.core.pipeline.PreprocessResult.Subtitle> subtitles)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(encodeCommand(source, output, scan, transportStream));
        if (!subtitles.isEmpty()) {
            for (var subtitle : subtitles) {
                if (subtitle.path.contains(",")) throw new IOException("Subtitle paths cannot contain commas");
            }
            command.addAll(List.of("--srt-file", String.join(",", subtitles.stream().map(s -> s.path).toList()),
                    "--srt-codeset", String.join(",", Collections.nCopies(subtitles.size(), "UTF-8")),
                    "--srt-lang", String.join(",", subtitles.stream().map(s -> s.language).toList())));
            for (int i = 0; i < subtitles.size(); i++) {
                if (subtitles.get(i).defaultTrack) {
                    command.addAll(List.of("--srt-default", Integer.toString(i + 1)));
                    break;
                }
            }
        }
        runner.run(command, log, Duration.ofHours(24), heartbeat);
    }
}
