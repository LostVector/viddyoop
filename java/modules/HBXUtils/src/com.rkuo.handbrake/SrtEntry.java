package com.rkuo.handbrake;

import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 11/4/12
 * Time: 7:06 PM
 * To change this template use File | Settings | File Templates.
 */
public class SrtEntry {
    public Long Index;
    public Long Start;
    public Long End;
    public String Text;

    public static boolean HasFormatting( List<SrtEntry> entries ) {

        boolean isFormatted = false;

        for( SrtEntry e : entries ) {
            if( e.Text.contains("<i>") == true ) {
                isFormatted = true;
                break;
            }

            if( e.Text.contains("<b>") == true ) {
                isFormatted = true;
                break;
            }

            if( e.Text.contains("<u>") == true ) {
                isFormatted = true;
                break;
            }

            if( e.Text.contains("</font>") == true ) {
                isFormatted = true;
                break;
            }
        }

        return isFormatted;
    }
}
