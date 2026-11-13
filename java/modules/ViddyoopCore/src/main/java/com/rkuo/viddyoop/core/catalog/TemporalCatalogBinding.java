package com.rkuo.viddyoop.core.catalog;

import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Catalog ownership evidence, not a mirror of workflow execution state. */
public final class TemporalCatalogBinding {
    private TemporalCatalogBinding() {}

    public static void verify(Path database, WorkflowServiceStubs service, String namespace) throws SQLException {
        String id = service.blockingStub().withDeadlineAfter(5, TimeUnit.SECONDS)
                .describeNamespace(DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build())
                .getNamespaceInfo().getId();
        bind(database, namespace, id);
    }

    static void bind(Path database, String namespace, String id) throws SQLException {
        if (namespace == null || namespace.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Temporal namespace name and UUID are required");
        }
        if (!Files.isRegularFile(database)) throw new IllegalArgumentException("Catalog must exist before binding its namespace: " + database);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()); var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS temporal_binding (singleton INTEGER PRIMARY KEY CHECK(singleton=1), namespace TEXT NOT NULL, namespace_id TEXT NOT NULL)");
            s.execute("BEGIN IMMEDIATE");
            try {
                boolean exists;
                try (var r = s.executeQuery("SELECT namespace,namespace_id FROM temporal_binding WHERE singleton=1")) {
                    exists = r.next();
                    if (exists && (!namespace.equals(r.getString(1)) || !id.equals(r.getString(2)))) {
                        throw new IllegalStateException("Temporal namespace identity differs from this catalog; restore the matching backend before starting workers");
                    }
                }
                if (!exists) {
                    boolean submissions;
                    try (var r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='submissions'")) { submissions = r.next(); }
                    if (submissions) {
                        try (var r = s.executeQuery("SELECT 1 FROM submissions LIMIT 1")) {
                            if (r.next()) throw new IllegalStateException("Unbound catalog has existing submissions; reconcile its original Temporal namespace before starting workers");
                        }
                    }
                    try (var insert = c.prepareStatement("INSERT INTO temporal_binding VALUES (1,?,?)")) {
                        insert.setString(1, namespace); insert.setString(2, id); insert.executeUpdate();
                    }
                }
                s.execute("COMMIT");
            } catch (SQLException | RuntimeException failure) {
                try { s.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
