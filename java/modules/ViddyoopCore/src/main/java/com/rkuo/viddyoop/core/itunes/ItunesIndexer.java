package com.rkuo.viddyoop.core.itunes;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import javax.xml.stream.*;

/** Streaming, read-only plist import. The previous catalog stays visible until a complete export commits. */
public final class ItunesIndexer {
    public record Result(String libraryId, int items, long indexedAt, long sourceModifiedAt) {}
    private static final Set<String> TRACK_FIELDS = Set.of("Track ID", "Persistent ID", "Location", "Name", "Kind", "Total Time", "Date Added");
    private static final Set<String> SCALARS = Set.of("string", "integer", "date");
    private final Path database;
    public ItunesIndexer(Path database) { this.database = database.toAbsolutePath().normalize(); }

    public Result index(Path source, Runnable heartbeat) throws IOException, SQLException, XMLStreamException {
        Path xml = source.toAbsolutePath().normalize();
        if (database.equals(xml) || (Files.exists(database) && Files.isSameFile(database, xml))) {
            throw new IOException("Catalog and iTunes export must be different files");
        }
        BasicFileAttributes before = Files.readAttributes(xml, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("iTunes export must be a regular XML file");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External XML resolution disabled"); });
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS itunes_items (
                      id INTEGER PRIMARY KEY, persistent_id TEXT, location TEXT, name TEXT,
                      kind TEXT, duration_ms INTEGER, date_added TEXT)
                    """);
                boolean persistentId = false;
                try (ResultSet columns = s.executeQuery("PRAGMA table_info(itunes_items)")) {
                    while (columns.next()) persistentId |= "persistent_id".equals(columns.getString("name"));
                }
                if (!persistentId) s.executeUpdate("ALTER TABLE itunes_items ADD COLUMN persistent_id TEXT");
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS itunes_index_meta (
                      id INTEGER PRIMARY KEY CHECK(id=1), library_id TEXT NOT NULL,
                      source_path TEXT NOT NULL, indexed_at INTEGER NOT NULL, source_modified_at INTEGER)
                    """);
                boolean sourceModified = false;
                try (ResultSet columns = s.executeQuery("PRAGMA table_info(itunes_index_meta)")) {
                    while (columns.next()) sourceModified |= "source_modified_at".equals(columns.getString("name"));
                }
                if (!sourceModified) s.executeUpdate("ALTER TABLE itunes_index_meta ADD COLUMN source_modified_at INTEGER");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS itunes_name_duration ON itunes_items(name,duration_ms)");
            }
            c.setAutoCommit(false);
            try (Statement clear = c.createStatement(); PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO itunes_items(id,persistent_id,location,name,kind,duration_ms,date_added) VALUES(?,?,?,?,?,?,?)")) {
                clear.executeUpdate("DELETE FROM itunes_items");
                String libraryId = null; int count = 0; boolean sawTracks = false;
                try (InputStream in = Files.newInputStream(xml, LinkOption.NOFOLLOW_LINKS)) {
                    XMLStreamReader reader = factory.createXMLStreamReader(in);
                    try {
                        while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                            if (reader.getEventType() == XMLStreamConstants.ENTITY_REFERENCE) throw new XMLStreamException("Entity references disabled");
                        }
                        expect(reader, reader.getEventType(), "plist");
                        expect(reader, reader.nextTag(), "dict");
                        while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                            expect(reader, reader.getEventType(), "key"); String key = text(reader);
                            int value = reader.nextTag();
                            if (key.equals("Tracks")) {
                                if (sawTracks) throw new XMLStreamException("Duplicate Tracks dictionary");
                                sawTracks = true; expect(reader, value, "dict");
                                while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                                    expect(reader, reader.getEventType(), "key");
                                    long outerId = number(text(reader), "track dictionary key");
                                    expect(reader, reader.nextTag(), "dict");
                                    Map<String,String> track = new HashMap<>();
                                    while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                                        expect(reader, reader.getEventType(), "key"); String field = text(reader);
                                        reader.nextTag();
                                        if (TRACK_FIELDS.contains(field)) {
                                            String scalar = scalar(reader);
                                            if (track.put(field, scalar) != null) throw new XMLStreamException("Duplicate track field: " + field);
                                        } else skip(reader);
                                    }
                                    end(reader, "dict");
                                    long id = track.containsKey("Track ID") ? number(track.get("Track ID"), "Track ID") : outerId;
                                    if (id <= 0 || id != outerId) throw new XMLStreamException("Invalid/mismatched track id");
                                    insert.setLong(1, id); insert.setString(2, track.get("Persistent ID"));
                                    insert.setString(3, track.get("Location")); insert.setString(4, track.get("Name"));
                                    insert.setString(5, track.get("Kind"));
                                    if (track.containsKey("Total Time")) {
                                        long duration = number(track.get("Total Time"), "Total Time");
                                        if (duration < 0) throw new XMLStreamException("Negative track duration");
                                        insert.setLong(6, duration);
                                    } else insert.setNull(6, Types.BIGINT);
                                    insert.setString(7, track.get("Date Added")); insert.executeUpdate(); count++;
                                    if (count % 100 == 0) heartbeat.run();
                                }
                                end(reader, "dict");
                            } else if (key.equals("Library Persistent ID")) {
                                if (libraryId != null) throw new XMLStreamException("Duplicate library identity");
                                libraryId = scalar(reader);
                            }
                            else skip(reader);
                        }
                        end(reader, "dict");
                        endAfterNext(reader, "plist");
                        while (reader.hasNext()) {
                            int token = reader.next();
                            if (token == XMLStreamConstants.START_ELEMENT || token == XMLStreamConstants.ENTITY_REFERENCE
                                    || (token == XMLStreamConstants.CHARACTERS && !reader.isWhiteSpace())) {
                                throw new XMLStreamException("Unexpected content after plist");
                            }
                        }
                    } finally { reader.close(); }
                }
                if (!sawTracks || libraryId == null || libraryId.isBlank()) throw new XMLStreamException("Missing Tracks/library identity");
                BasicFileAttributes after = Files.readAttributes(xml, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !before.creationTime().equals(after.creationTime())) {
                    throw new IOException("iTunes export changed during indexing");
                }
                long indexedAt = System.currentTimeMillis();
                try (PreparedStatement meta = c.prepareStatement("""
                    INSERT INTO itunes_index_meta(id,library_id,source_path,indexed_at,source_modified_at) VALUES(1,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET library_id=excluded.library_id,source_path=excluded.source_path,indexed_at=excluded.indexed_at,source_modified_at=excluded.source_modified_at
                    """)) {
                    meta.setString(1, libraryId); meta.setString(2, xml.toString()); meta.setLong(3, indexedAt); meta.setLong(4, before.lastModifiedTime().toMillis()); meta.executeUpdate();
                }
                heartbeat.run(); c.commit(); return new Result(libraryId, count, indexedAt, before.lastModifiedTime().toMillis());
            } catch (SQLException | IOException | XMLStreamException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    private static String text(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder value = new StringBuilder();
        while (reader.hasNext()) {
            int token = reader.next();
            if (token == XMLStreamConstants.END_ELEMENT) return value.toString();
            if (token == XMLStreamConstants.ENTITY_REFERENCE || token == XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("Unexpected entity/nested text element");
            }
            if (token == XMLStreamConstants.CHARACTERS || token == XMLStreamConstants.CDATA || token == XMLStreamConstants.SPACE) {
                value.append(reader.getText());
                if (value.length() > 1024 * 1024) throw new XMLStreamException("Plist scalar exceeds limit");
            }
        }
        throw new XMLStreamException("Incomplete plist text");
    }

    private static long number(String text, String field) throws XMLStreamException {
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new XMLStreamException("Invalid " + field, e); }
    }
    private static String scalar(XMLStreamReader reader) throws XMLStreamException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT
                || !SCALARS.contains(reader.getLocalName())) throw new XMLStreamException("Expected scalar plist value");
        return text(reader);
    }
    private static void skip(XMLStreamReader reader) throws XMLStreamException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) throw new XMLStreamException("Expected plist value");
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int token = reader.next();
            if (token == XMLStreamConstants.START_ELEMENT) depth++;
            else if (token == XMLStreamConstants.END_ELEMENT) depth--;
            else if (token == XMLStreamConstants.ENTITY_REFERENCE) throw new XMLStreamException("Entity references disabled");
        }
        if (depth != 0) throw new XMLStreamException("Incomplete plist value");
    }
    private static void expect(XMLStreamReader reader, int token, String name) throws XMLStreamException {
        if (token != XMLStreamConstants.START_ELEMENT || !name.equals(reader.getLocalName())) throw new XMLStreamException("Expected " + name);
    }
    private static void end(XMLStreamReader reader, String name) throws XMLStreamException {
        if (reader.getEventType() != XMLStreamConstants.END_ELEMENT || !name.equals(reader.getLocalName())) throw new XMLStreamException("Expected end of " + name);
    }
    private static void endAfterNext(XMLStreamReader reader, String name) throws XMLStreamException { reader.nextTag(); end(reader, name); }
}
