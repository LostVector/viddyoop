package com.rkuo.util;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@RunWith(JUnit4.class)
public class FileUtilsTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testListFilesFiltersByExtensionAndDoesNotRecurse() throws Exception {
        File dir = tempFolder.newFolder("listdir");
        Files.write(new File(dir, "a.txt").toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "b.TXT").toPath(), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "c.mkv").toPath(), "c".getBytes(StandardCharsets.UTF_8));

        File sub = new File(dir, "sub");
        Assert.assertTrue(sub.mkdir());
        Files.write(new File(sub, "d.txt").toPath(), "d".getBytes(StandardCharsets.UTF_8));

        String[] files = FileUtils.ListFiles(dir, ".txt");
        Assert.assertNotNull(files);
        // extension matching is case-insensitive, but subdirectories are not searched
        Set<String> names = new HashSet<String>();
        for (String f : files) {
            names.add(new File(f).getName());
        }
        Assert.assertEquals(new HashSet<String>(Arrays.asList("a.txt", "b.TXT")), names);
    }

    @Test
    public void testListFilesOnMissingDirectoryReturnsNull() {
        File missing = new File(tempFolder.getRoot(), "does_not_exist");
        Assert.assertNull(FileUtils.ListFiles(missing, ".txt"));
    }

    @Test
    public void testGetFilesRecurses() throws Exception {
        File dir = tempFolder.newFolder("recurse");
        Files.write(new File(dir, "top.mkv").toPath(), "t".getBytes(StandardCharsets.UTF_8));

        File sub = new File(dir, "sub/nested");
        Assert.assertTrue(sub.mkdirs());
        Files.write(new File(sub, "deep.mkv").toPath(), "d".getBytes(StandardCharsets.UTF_8));

        String[] files = FileUtils.GetFiles(dir);
        Assert.assertNotNull(files);
        Assert.assertEquals(2, files.length);
    }

    @Test
    public void testGetDirectoriesRecurses() throws Exception {
        File dir = tempFolder.newFolder("dirs");
        new File(dir, "one").mkdir();
        new File(dir, "two/three").mkdirs();

        String[] dirs = FileUtils.GetDirectories(dir);
        Assert.assertNotNull(dirs);
        Assert.assertEquals(3, dirs.length);
    }

    @Test
    public void testPathCombine() {
        String combined = FileUtils.PathCombine("one", "two");
        Assert.assertEquals(new File("one", "two").getPath(), combined);
    }

    @Test
    public void testCopy() throws Exception {
        File source = tempFolder.newFile("source.bin");
        byte[] payload = new byte[100000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Files.write(source.toPath(), payload);

        File target = new File(tempFolder.getRoot(), "target.bin");
        Assert.assertTrue(FileUtils.Copy(source.getAbsolutePath(), target.getAbsolutePath()));
        Assert.assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void testCopyMissingSourceReturnsFalse() {
        File missing = new File(tempFolder.getRoot(), "nope.bin");
        File target = new File(tempFolder.getRoot(), "target.bin");
        Assert.assertFalse(FileUtils.Copy(missing.getAbsolutePath(), target.getAbsolutePath()));
    }

    @Test
    public void testMoveFileIntoDirectory() throws Exception {
        File source = tempFolder.newFile("movable.txt");
        File dir = tempFolder.newFolder("move-target");

        Assert.assertTrue(FileUtils.MoveFile(source.getAbsolutePath(), dir.getAbsolutePath()));
        Assert.assertFalse(source.exists());
        Assert.assertTrue(new File(dir, "movable.txt").exists());
    }

    @Test
    public void testMoveFileToExplicitTarget() throws Exception {
        File source = tempFolder.newFile("rename-me.txt");
        File target = new File(tempFolder.getRoot(), "renamed.txt");

        Assert.assertTrue(FileUtils.MoveFile(source.getAbsolutePath(), target.getAbsolutePath()));
        Assert.assertFalse(source.exists());
        Assert.assertTrue(target.exists());
    }

    @Test
    public void testRemoveDirectory() throws Exception {
        File root = tempFolder.newFolder("remove-me");
        File nested = new File(root, "a/b/c");
        Assert.assertTrue(nested.mkdirs());
        Files.write(new File(nested, "file.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        Assert.assertTrue(FileUtils.RemoveDirectory(root));
        Assert.assertFalse(root.exists());
    }

    @Test
    public void testCleanDirectoryKeepsDirectoryItself() throws Exception {
        File root = tempFolder.newFolder("clean-me");
        File nested = new File(root, "a/b");
        Assert.assertTrue(nested.mkdirs());
        Files.write(new File(nested, "file.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        Assert.assertTrue(FileUtils.CleanDirectory(root));
        Assert.assertTrue(root.exists());
        Assert.assertEquals(0, root.list().length);
    }

    @Test
    public void testGetNameWithoutExtension() {
        Assert.assertEquals("movie", FileUtils.getNameWithoutExtension("movie.m4v"));
        Assert.assertEquals("movie", FileUtils.getNameWithoutExtension("/some/path/movie.m4v"));
        Assert.assertEquals("a.b", FileUtils.getNameWithoutExtension("a.b.mkv"));

        // no extension
        Assert.assertNull(FileUtils.getNameWithoutExtension("movie"));
        // trailing dot
        Assert.assertNull(FileUtils.getNameWithoutExtension("movie."));
        // leading dot only counts as hidden file, not extension
        Assert.assertNull(FileUtils.getNameWithoutExtension(".hidden"));
    }

    @Test
    public void testGetAbsolutePathWithoutExtension() {
        String absolute = new File("movie.mkv").getAbsolutePath();
        String result = FileUtils.getAbsolutePathWithoutExtension("movie.mkv");
        Assert.assertEquals(absolute.substring(0, absolute.lastIndexOf('.')), result);

        // a path with no dot in the final component yields null (assuming the
        // working directory itself contains no dot)
        String plain = new File("movie").getAbsolutePath();
        if (plain.lastIndexOf('.') <= 0) {
            Assert.assertNull(FileUtils.getAbsolutePathWithoutExtension("movie"));
        }
    }
}
