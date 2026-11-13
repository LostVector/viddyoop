package com.rkuo.viddyoop.core.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rkuo.subtitles.SrtParser;
import com.rkuo.viddyoop.core.pipeline.PreprocessResult;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Remuxes owned MKVs and repairs extracted text subtitles without changing the snapshot. */
public final class MediaNormalizer {
    private final String merge, extract, ffmpeg;
    private final MediaProcessRunner runner;
    public MediaNormalizer(String merge, String extract, String ffmpeg, MediaProcessRunner runner) {
        this.merge = merge; this.extract = extract; this.ffmpeg = ffmpeg; this.runner = runner;
    }

    public PreprocessResult normalize(Path source, boolean matroska, Path attempt, Runnable heartbeat)
            throws IOException, InterruptedException {
        PreprocessResult result = new PreprocessResult();
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
                // Reuse the durable subtitle repair behavior from HBXUtils.
                var entries = SrtParser.parseFileStrict(srt.toString());
                if (entries == null) entries = SrtParser.parseFileLoose(srt.toString());
                if (entries == null || entries.isEmpty()) throw new IOException("Invalid subtitle: " + srt);
                Path repaired = attempt.resolve("subtitle-" + id + "-repaired.srt");
                if (!SrtParser.writeFile(repaired.toString(), entries)) throw new IOException("Cannot write repaired subtitles");
                var subtitle = new PreprocessResult.Subtitle();
                subtitle.path = repaired.toString();
                subtitle.checksum = MediaFiles.checksum(repaired, heartbeat);
                subtitle.language = properties.path("language").asText("und");
                subtitle.defaultTrack = properties.path("default_track").asBoolean();
                result.subtitles.add(subtitle);
            }
        }
        result.stagedPath = normalized.toString();
        result.checksum = MediaFiles.checksum(normalized, heartbeat);
        return result;
    }
}
