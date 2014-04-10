package com.rkuo.util;

import java.io.*;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Collections;

import com.rkuo.logging.RKLog;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 8:46:44 PM
 * To change this template use File | Settings | File Templates.
 */
public class FileUtils {

    // Note ... the . needs to be specified in the file extension.  AKA .txt, not txt.
    // this function returns all files in the specified directory.  It does not recurse into subdirectories.
    public static String[] ListFiles(File fIn, String fileExtension) {

        ArrayList<String> fileNames;
        File[] files;

        fileNames = new ArrayList<String>();

        files = fIn.listFiles();
        if (files == null) {
            return null;
        }

        for (File f : files) {
            String fileName;

            if (f.isDirectory() == true) {
                continue;
            }

            fileName = f.getName();
            fileName = fileName.toLowerCase();

            if (fileName.endsWith(fileExtension) == false) {
                continue;
            }

            fileNames.add(f.getAbsolutePath());
        }

        return fileNames.toArray(new String[fileNames.size()]);
    }

    public static boolean Copy(String source, String target) {

        try {
            File f1 = new File(source);
            File f2 = new File(target);
            byte[] buf = new byte[65536];
            int len;

            InputStream in = new FileInputStream(f1);
            OutputStream out = new FileOutputStream(f2);

            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }

            in.close();
            out.close();
//            System.out.println("File copied.");
        }
        catch (FileNotFoundException ex) {
            System.out.println(ex.getMessage() + " in the specified directory.");
            return false;
        }
        catch (IOException ioex) {
            System.out.println(ioex.getMessage());
            return false;
        }

        return true;
    }

    // Fault tolerant copy
    public static void MoveFiles(String[] fileNames, String targetDirectory) {

        for (String fileName : fileNames) {
            MoveFile(fileName, targetDirectory);
        }

        return;
    }

    // accept either a file or directory target ... same drive only
    public static boolean MoveFile(String sourceFilename, String target) {

        File fSource, fTarget;
        boolean br;

        fSource = new File(sourceFilename);

        fTarget = new File(target);
        if (fTarget.isDirectory() == true) {
            fTarget = new File(target, fSource.getName());
        }

        br = fSource.renameTo(fTarget);
        if (br == false) {
            return false;
        }

        return true;
    }

    public static String PathCombine(String path1, String path2) {
        File file1 = new File(path1);
        File file2 = new File(file1, path2);
        return file2.getPath();
    }

    // Deletes all files and subdirectories under the target directory, including the target directory itself.
    // Returns true if all deletions were successful.
    // If a deletion fails, the method stops attempting to delete and returns false.
    public static boolean RemoveDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                boolean success = RemoveDirectory(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

        // The directory is now empty so delete it
        return dir.delete();
    }

    // Deletes all files and subdirectories in the target directory
    // Does not remove the target directory itself.
    // Returns true if all deletions were successful.
    // If a deletion fails, the method stops attempting to delete and returns false.
    public static boolean CleanDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                boolean success = RemoveDirectory(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

        return true;
    }

    // Note ... the . needs to be specified in the file extension.  AKA .txt, not txt.
    // This returns all files in the specified directory and its subdirectories.
    public static String[] GetFiles(File fIn) {

        ArrayList<String> fileNames;
        File[] files;

        fileNames = new ArrayList<String>();

        files = fIn.listFiles();
        if( files == null ) {
            return null;
        }

        for (File f : files) {
            if (f.isDirectory() == true) {
                String[] subFileNames;

                subFileNames = GetFiles(f);
                Collections.addAll(fileNames, subFileNames);
            } else {
                fileNames.add(f.getAbsolutePath());
            }
        }

        return fileNames.toArray(new String[fileNames.size()]);
    }

    // Note ... the . needs to be specified in the file extension.  AKA .txt, not txt.
    public static String[] GetDirectories(File fIn) {

        ArrayList<String> aDirectories;
        File[] files;

        aDirectories = new ArrayList<String>();

        files = fIn.listFiles();
        for (File f : files) {
            String[] subDirectories;

            if (f.isDirectory() == false) {
                continue;
            }

            aDirectories.add(f.getAbsolutePath());
            subDirectories = GetDirectories(f);
            Collections.addAll(aDirectories, subDirectories);
        }

        return aDirectories.toArray(new String[aDirectories.size()]);
    }

    // returns the absolute path of the file without extension
    public static String getAbsolutePathWithoutExtension(String fileName) {
        File file = new File(fileName);

        int index = file.getAbsolutePath().lastIndexOf('.');
        if (index <= 0) {
            return null;
        }

        if (index > file.getAbsolutePath().length() - 2) {
            return null;
        }

        return file.getAbsolutePath().substring(0, index);
    }

    // returns the base name of the file (without the preceding path or the following extension ... just the name)
    // filename.m4v becomes filename
    public static String getNameWithoutExtension(String fileName) {
        File file = new File(fileName);

        int index = file.getName().lastIndexOf('.');
        if (index <= 0) {
            return null;
        }

        if (index > file.getName().length() - 2) {
            return null;
        }

        return file.getName().substring(0, index);
    }
}