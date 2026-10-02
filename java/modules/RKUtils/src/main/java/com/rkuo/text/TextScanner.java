package com.rkuo.text;

import java.io.*;

public class TextScanner {

    // look for windows linefeeds
    public static Boolean HasWindowsLinefeeds( File f ) throws IOException {

        FileInputStream fis;
        InputStreamReader isr;
        BufferedReader reader;

        fis = new FileInputStream(f.getAbsolutePath());
        isr = new InputStreamReader(fis);
        reader = new BufferedReader(isr);
        while( true ) {
            int c;

            c = reader.read();
            if( c == -1 ) {
                break;
            }

            if( c == '\r' ) {
                c = reader.read();
                if( c == '\n' ) {
                    return true;
                }
            }
        }

        return false;
    }
}
