package com.rkuo.io;

import com.rkuo.logging.RKLog;
import com.rkuo.util.Misc;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 4, 2010
 * Time: 5:08:21 PM
 * To change this template use File | Settings | File Templates.
 */
public class File {

    public static String ToString(String filePath) {
        byte[] buffer;
        BufferedInputStream bis;
        java.io.File    f;

        bis = null;

        f = new java.io.File(filePath);                
        buffer = new byte[(int) f.length()];

        try {
            bis = new BufferedInputStream(new FileInputStream(filePath));
            bis.read(buffer);
        }
        catch( IOException ioex ) {
            return null;
        }
        finally {
            if( bis != null ) try {
                bis.close();
            }
            catch( IOException ignored ) {
            }
        }

        return new String(buffer);
    }

    public static boolean ReliableCopy(String source, String target, long timeout) {

        java.io.File fSource, fTarget;
        long    lastSuccess;
        long    lastPosition;
        boolean bFinished;
        byte[]  buf;

        buf = new byte[65536];

        lastSuccess = System.currentTimeMillis();
        lastPosition = 0;
        bFinished = false;

        fSource = new java.io.File(source);
        fTarget = new java.io.File(target);
        fTarget.delete();

        while( true ) {
            FileInputStream fisSource;
            RandomAccessFile rafTarget;

            fisSource = null;
            rafTarget = null;

            try {
//                FileOutputStream fosTarget;
                FileChannel fcSource;

                RKLog.Log( "Starting fault tolerant copy at position %d.", lastPosition );

                fisSource = new FileInputStream(fSource);
                rafTarget = new RandomAccessFile(target, "rw");
//                fosTarget = new FileOutputStream(fTarget, true);

                fcSource = fisSource.getChannel();
                fcSource.position( lastPosition );
//                fcTarget = fosTarget.getChannel();

//                fcTarget.position( lastPosition );
                rafTarget.seek( lastPosition );
                
                while( true ) {

                    int len;

//                    if( Math.random() > 0.99 ) {
//                        throw new IOException( "Test throw" );
//                   }

                    len = fisSource.read( buf );
                    if( len <= 0 ) {
                        bFinished = true;
                        break;
                    }

//                    fosTarget.write(buf, 0, len);
                    rafTarget.write(buf, 0, len);

                    lastPosition = fcSource.position();
                    lastSuccess = System.currentTimeMillis();
                }
            }
            catch (FileNotFoundException ex) {
                RKLog.Log( ex.getMessage() + " in the specified directory." );
            }
            catch (IOException ioex) {
                RKLog.Log( ioex.getMessage() );
            }
            finally {
                Misc.close(fisSource);
                Misc.close( rafTarget );
            }

            if( bFinished == true ) {
                break;
            }

            if( System.currentTimeMillis() > lastSuccess + timeout ) {
                RKLog.Log( "Timeout exceeded: %d ms", timeout );
                break;
            }

            try {
                Thread.sleep( 60000 );
            }
            catch( InterruptedException iex ) {
                // do nothing
            }
        }

        return bFinished;
    }

    // returns the file extension (no dot)
    // does not require the file to exist
    public static String GetExtension( String fullPath ) {
        int dot;

        dot = fullPath.lastIndexOf(".");
        if( dot == -1 ) {
            return null;
        }
        
        return fullPath.substring(dot + 1);
    }

    // Note: the . needs to be specified in the file extension.  AKA .txt, not txt.
    public static String[] GetFilesWithExtension(java.io.File fIn, String fileExtension) {

        ArrayList<String> fileNames;
        java.io.File[] files;

        fileNames = new ArrayList<String>();

        files = fIn.listFiles();
        if( files == null ) {
            return new String[0];
        }

        for (java.io.File f : files) {
            if (f.isDirectory() == true) {
                String[] subFileNames;

                subFileNames = GetFilesWithExtension(f, fileExtension);
                Collections.addAll(fileNames, subFileNames);
            } else {
                String fileName;

                fileName = f.getName();
                fileName = fileName.toLowerCase();

                if (fileName.endsWith(fileExtension) == true) {
                    fileNames.add(f.getAbsolutePath());
                }
            }
        }

        return fileNames.toArray(new String[fileNames.size()]);
    }

    public static boolean WriteString(String text, java.io.File f) {
        FileOutputStream fop = null;

        try {
            byte[] bytes;

            fop = new FileOutputStream(f);

            bytes = text.getBytes();

            fop.write(bytes);
            fop.flush();
            fop.close();
        }
        catch( IOException e ) {
            e.printStackTrace();
            return false;
        }
        finally {
            try {
                if( fop != null ) {
                    fop.close();
                }
            }
            catch( IOException e ) {
                e.printStackTrace();
            }
        }

        return true;
    }
}
