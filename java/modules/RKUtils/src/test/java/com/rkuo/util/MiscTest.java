package com.rkuo.util;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class MiscTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testGetTimeString() {
        Assert.assertEquals("00:00:00", Misc.GetTimeString(0));
        Assert.assertEquals("00:00:01", Misc.GetTimeString(1000));
        // sub-second remainders are truncated
        Assert.assertEquals("00:00:01", Misc.GetTimeString(1999));
        Assert.assertEquals("00:00:59", Misc.GetTimeString(59999));
        Assert.assertEquals("01:02:03", Misc.GetTimeString(3723000));
        Assert.assertEquals("00:01:00", Misc.GetTimeString(60000));
    }

    @Test
    public void testCloseNullIsSafe() {
        Misc.close(null);
    }

    @Test
    public void testCloseSwallowsIOException() {
        Closeable boom = new Closeable() {
            @Override
            public void close() throws IOException {
                throw new IOException("boom");
            }
        };
        Misc.close(boom);
    }

    @Test
    public void testExecuteProcessSuccess() {
        int exitCode = Misc.ExecuteProcess(new String[]{"/bin/echo", "hello"});
        Assert.assertEquals(0, exitCode);
    }

    @Test
    public void testExecuteProcessReturnsExitCode() {
        int exitCode = Misc.ExecuteProcess(
                new String[]{"/bin/sh", "-c", "echo out; exit 3"}, null, false, null, null);
        Assert.assertEquals(3, exitCode);
    }

    @Test
    public void testExecuteProcessMissingBinaryReturnsMaxValue() {
        int exitCode = Misc.ExecuteProcess(new String[]{"/nonexistent/no-such-binary"}, null, false, null, null);
        Assert.assertEquals(Integer.MAX_VALUE, exitCode);
    }

    @Test
    public void testExecuteProcessWritesOutputFileAndInvokesCallback() throws Exception {
        File outputFile = new File(tempFolder.getRoot(), "process-output.txt");
        final List<String> callbackLines = new ArrayList<String>();

        int exitCode = Misc.ExecuteProcess(
                new String[]{"/bin/sh", "-c", "echo line-one; echo line-two"},
                null,
                false,
                outputFile.getAbsolutePath(),
                new ExecuteProcessCallback() {
                    @Override
                    public void ProcessLine(String line) {
                        callbackLines.add(line);
                    }
                });

        Assert.assertEquals(0, exitCode);

        String contents = new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(contents.contains("line-one"));
        Assert.assertTrue(contents.contains("line-two"));

        Assert.assertEquals(2, callbackLines.size());
        Assert.assertEquals("line-one", callbackLines.get(0));
        Assert.assertEquals("line-two", callbackLines.get(1));
    }

    @Test
    public void testExecuteProcessUsesWorkingDirectory() throws Exception {
        File workDir = tempFolder.newFolder("workdir");

        int exitCode = Misc.ExecuteProcess(
                new String[]{"/bin/sh", "-c", "pwd"}, workDir.getAbsolutePath(), false, null, null);
        Assert.assertEquals(0, exitCode);
    }
}
