package com.rkuo.viddyoop.core.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.subtitles.SrtParser;
import com.rkuo.viddyoop.core.pipeline.PreprocessResult;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Remuxes owned MKVs and repairs embedded text subtitles without changing the snapshot. */
public final class MediaNormalizer {
    public static final int SUBTITLE_POLICY_VERSION = 2;
    private final String merge, extract, ffmpeg;
    private final MediaProcessRunner runner;
    public MediaNormalizer(String merge, String extract, String ffmpeg, MediaProcessRunner runner) {
        this.merge = merge; this.extract = extract; this.ffmpeg = ffmpeg; this.runner = runner;
    }

    public PreprocessResult normalize(Path source, boolean matroska, Path attempt, Runnable heartbeat)
            throws IOException, InterruptedException {
        PreprocessResult result = new PreprocessResult();
        result.subtitlePolicyVersion = SUBTITLE_POLICY_VERSION;
        Path normalized = source;
        if (matroska) {
            normalized = attempt.resolve("normalized.mkv");
            runner.run(List.of(merge, "--output", normalized.toString(), source.toString()),
                    attempt.resolve("remux.log"), Duration.ofMinutes(30), heartbeat);
            Path identification = attempt.resolve("tracks.json");
            runner.run(List.of(merge, "--identification-format", "json", "--identify", normalized.toString()),
                    identification, Duration.ofMinutes(5), heartbeat);
            if (Files.size(identification) > 16 * 1024 * 1024) throw new IOException("Track identification too large");
            var root = new ObjectMapper().readTree(identification.toFile());
            if (!root.path("tracks").isArray()) throw new IOException("Missing track identification");
            for (var track : root.path("tracks")) {
                if (!"subtitles".equals(track.path("type").asText())) continue;
                var properties = track.path("properties");
                String codec = properties.path("codec_id").asText();
                String extension = switch (codec) {
                    case "S_TEXT/UTF8" -> "srt";
                    case "S_TEXT/ASS" -> "ass";
                    case "S_TEXT/SSA" -> "ssa";
                    default -> throw new IOException("Subtitle codec requires review: " + codec);
                };
                int id = track.path("id").asInt(-1);
                if (id < 0) throw new IOException("Invalid subtitle track id");
                Path raw = attempt.resolve("subtitle-" + id + "." + extension);
                runner.run(List.of(extract, normalized.toString(), "tracks", id + ":" + raw),
                        attempt.resolve("subtitle-" + id + "-extract.log"), Duration.ofMinutes(30), heartbeat);
                Path srt = raw;
                if (!extension.equals("srt")) {
                    srt = attempt.resolve("subtitle-" + id + ".srt");
                    runner.run(List.of(ffmpeg, "-nostdin", "-y", "-i", raw.toString(), srt.toString()),
                            attempt.resolve("subtitle-" + id + "-convert.log"), Duration.ofMinutes(5), heartbeat);
                }
                result.subtitles.add(repair(srt, attempt, id, properties.path("language").asText("und"),
                        properties.path("default_track").asBoolean(), heartbeat));
            }
        } else {
            Path identification = attempt.resolve("tracks.json");
            String probe = Path.of(ffmpeg).resolveSibling("ffprobe").toString();
            runner.run(List.of(probe, "-v", "error", "-show_entries",
                    "stream=index,codec_type,codec_name:stream_tags=language:stream_disposition=default,forced:format=format_name",
                    "-of", "json", source.toString()), identification, Duration.ofMinutes(5), heartbeat);
            if (Files.size(identification) > 16 * 1024 * 1024) throw new IOException("Track identification too large");
            var root = new ObjectMapper().readTree(identification.toFile());
            if (!root.path("streams").isArray()) throw new IOException("Missing stream identification");
            var formats = Arrays.asList(root.path("format").path("format_name").asText().split(","));
            boolean isoMedia = formats.contains("mov") || formats.contains("mp4");
            var extraction = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-y", "-i", source.toString()));
            var tracks = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (var track : root.path("streams")) {
                if (!"subtitle".equals(track.path("codec_type").asText())) continue;
                String codec = track.path("codec_name").asText();
                if (!Set.of("mov_text", "subrip", "ass", "ssa", "text", "webvtt").contains(codec)) {
                    throw new IOException("Subtitle codec requires review: " + codec);
                }
                int id = track.path("index").asInt(-1);
                if (id < 0) throw new IOException("Invalid subtitle stream index");
                Path srt = attempt.resolve("subtitle-" + id + ".srt");
                extraction.addAll(List.of("-map", "0:" + id, "-c:s", "srt", srt.toString()));
                tracks.add(track);
            }
            // One pass over large NAS snapshots, rather than rereading the
            // complete movie separately for every subtitle language.
            if (!tracks.isEmpty()) runner.run(extraction, attempt.resolve("subtitle-extract.log"), Duration.ofMinutes(30), heartbeat);
            for (var track : tracks) {
                int id = track.path("index").asInt();
                Path srt = attempt.resolve("subtitle-" + id + ".srt");
                result.subtitles.add(repair(srt, attempt, id, track.path("tags").path("language").asText("und"),
                        // MP4 default means enabled/discoverable, not automatic
                        // display. HandBrake encodes its SRT default as forced.
                        track.path("disposition").path(isoMedia ? "forced" : "default").asInt() == 1, heartbeat));
            }
        }
        result.stagedPath = normalized.toString();
        result.checksum = MediaFiles.checksum(normalized, heartbeat);
        return result;
    }

    private static PreprocessResult.Subtitle repair(Path srt, Path attempt, int id, String language,
                                                     boolean defaultTrack, Runnable heartbeat) throws IOException {
        // Reuse the durable subtitle repair behavior from HBXUtils for both containers.
        var entries = SrtParser.parseFileStrict(srt.toString());
        if (entries == null) entries = SrtParser.parseFileLoose(srt.toString());
        if (entries == null || entries.isEmpty()) throw new IOException("Invalid subtitle: " + srt);
        Path repaired = attempt.resolve("subtitle-" + id + "-repaired.srt");
        if (!SrtParser.writeFile(repaired.toString(), entries)) throw new IOException("Cannot write repaired subtitles");
        var subtitle = new PreprocessResult.Subtitle();
        subtitle.path = repaired.toString();
        subtitle.checksum = MediaFiles.checksum(repaired, heartbeat);
        subtitle.language = language;
        subtitle.defaultTrack = defaultTrack;
        return subtitle;
    }
}
