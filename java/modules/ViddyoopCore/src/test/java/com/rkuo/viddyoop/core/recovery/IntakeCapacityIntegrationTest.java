package com.rkuo.viddyoop.core.recovery;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.worker.PipelineHeartbeat;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** A source copy must not consume the headroom needed to start its own workflow. */
public class IntakeCapacityIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void defersBeforeCopyWhenOnlyWorkflowEstimateFits() throws Exception {
        String jar=System.getProperty("viddyoop.jar"),target=System.getProperty("viddyoop.temporal"),fullfs=System.getProperty("viddyoop.fullfs");
        Assume.assumeTrue(jar!=null&&target!=null&&fullfs!=null);
        Path mount=Path.of(fullfs).toRealPath();long total=Files.getFileStore(mount).getTotalSpace();
        Assert.assertTrue("Requires disposable filesystem <=64 MiB",total>0&&total<=64L*1024*1024);
        Path work=Files.createTempDirectory(mount,"viddyoop-intake-"),root=temp.getRoot().toPath(),inbox=root.resolve("inbox"),library=root.resolve("library"),db=root.resolve("catalog.db");
        Process worker=null;
        try {
            Files.createDirectories(inbox);Files.createDirectories(library);
            Path original=inbox.resolve("movie.mkv");Files.write(original,new byte[2*1024*1024]);
            // 4x (8 MiB) fits, but the additional 2 MiB snapshot would make it fail.
            long floor=Files.getFileStore(work).getUsableSpace()-9L*1024*1024;
            Assert.assertTrue(floor>=0);
            Path log=root.resolve("worker.log");
            var command=List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",jar,"pipeline",
                    "--inbox",inbox.toString(),"--work",work.toString(),"--library",library.toString(),"--database",db.toString(),
                    "--archive-policy","none","--temporal",target,"--queue","intake-"+UUID.randomUUID(),
                    "--settle-ms","0","--poll-ms","100","--min-free-bytes",Long.toString(floor),
                    "--handbrake",root.resolve("media-tool-must-not-run").toString());
            var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("VIDYOOP_"));
            worker=builder.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean deferred=false;
            while(System.nanoTime()<deadline) {
                if(!worker.isAlive())Assert.fail(Files.readString(log));
                if(PipelineHeartbeat.fresh(work,System.currentTimeMillis())&&Files.readString(log).contains("Deferring input: insufficient scratch capacity")){deferred=true;break;}
                Thread.sleep(100);
            }
            Assert.assertTrue(Files.readString(log),deferred);
            try(var staged=Files.list(work.resolve("ingest"))) {Assert.assertEquals("Input was copied before capacity admission",0,staged.count());}
            Assert.assertTrue(new SubmissionCatalog(db).pendingStarts().isEmpty());
            Assert.assertEquals(2L*1024*1024,Files.size(original));Assert.assertTrue(worker.isAlive());
        } finally {
            if(worker!=null){worker.destroy();if(!worker.waitFor(10,TimeUnit.SECONDS)){worker.destroyForcibly();worker.waitFor(10,TimeUnit.SECONDS);}}
            try(var files=Files.walk(work)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
