package com.rkuo.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 9, 2010
 * Time: 1:49:37 PM
 * To change this template use File | Settings | File Templates.
 */
public class Stream {

    public static String ToString(InputStream is) {

          StringBuilder sb;

          sb = new StringBuilder();

          try {
              BufferedReader reader;
              String line;

              reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

              while( true ) {
                  line = reader.readLine();
                  if( line == null ) {
                      break;
                  }

                  sb.append(line + "\n");
              }
          }
          catch( IOException ioex ) {
              return null;
          }

          return sb.toString();
      }
    
}
