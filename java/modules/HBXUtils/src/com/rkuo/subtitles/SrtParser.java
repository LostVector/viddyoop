package com.rkuo.subtitles;

import com.rkuo.logging.RKLog;
import com.rkuo.text.UnicodeInputStream;
import com.rkuo.util.Misc;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a .srt file and creates a Track for it.
 */
public class SrtParser {

    public static boolean writeFile(String filename, List<SrtEntry> entries) {
        BufferedWriter writer = null;

        try {
            //Construct the BufferedWriter object
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename),"UTF-8"));

            for (SrtEntry e : entries) {
                Long h,m,s,ms;

                writer.write(String.format("%d\n",e.Index));

                h = e.Start / (60*60*1000);
                m = e.Start % (60*60*1000) / (60*1000);
                s = e.Start % (60*1000) / 1000;
                ms = e.Start % 1000;
                writer.write(String.format("%02d:%02d:%02d,%03d --> ",h,m,s,ms));

                h = e.End / (60*60*1000);
                m = e.End % (60*60*1000) / (60*1000);
                s = e.End % (60*1000) / 1000;
                ms = e.End % 1000;
                writer.write(String.format("%02d:%02d:%02d,%03d\n",h,m,s,ms));

                writer.write(e.Text);
                writer.write("\n");
            }
        }
        catch (FileNotFoundException ex) {
            ex.printStackTrace();
            return false;
        }
        catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }
        finally {
            //Close the BufferedWriter
            try {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        RKLog.Log("Wrote %d SRT entries.",entries.size());
        return true;
    }

    // this tries to be a robust parser.
    // It handles blank lines in bad places between sequence entries
    public static List<SrtEntry> parseFileLoose(String filename) {
        List<SrtEntry> entries = new ArrayList<SrtEntry>();
        String numberString;
        Long sequenceExpected = 1L;

        FileInputStream fis;
        UnicodeInputStream uis;
        InputStreamReader isr;
        BufferedReader r;

        try {
            fis = new FileInputStream(filename);
            uis = new UnicodeInputStream(fis);
            uis.skipBOM(); // we're just going to skip the BOM all the time and assume it is UTF-8
            isr = new InputStreamReader(uis,"UTF-8");
            r = new BufferedReader(isr);
        }
        catch (Exception ex) {
            return null;
        }

        try {
            numberString = r.readLine();

            while (true) {
                String s;
                String timeString;

                String lineString = "";
                SrtEntry e = new SrtEntry();

                if (numberString == null) {
                    break;
                }

                if (numberString.length() == 0) {
                    continue;
                }

                try {
                    e.Index = Long.parseLong(numberString);
                    if (e.Index.equals(sequenceExpected) == false) {
                        throw new Exception(String.format("Expected sequence number %d, got %d", sequenceExpected, e.Index));
                    }
                }
                catch (Exception ex) {
                    throw ex;
                }

                sequenceExpected++;

                timeString = r.readLine();
                e.Start = parse(timeString.split("-->")[0]);
                e.End = parse(timeString.split("-->")[1]);

                // this loop reads lines after a sequence number and timestamp
                // but tries to account for blank lines that are in bad places
                while (true) {
                    s = r.readLine();
                    if (s == null) {
                        break;
                    }

                    if (s.trim().equals("") == true) {
                        // this is a blank line
                        if (lineString.length() == 0) {
                            // there are one or more blank lines at the beginning of the entry.  Ignore them.
                            RKLog.Log("SrtParser::parseFileLoose - Unexpected leading blank lines at index %d.", e.Index);
                            continue;
                        }

                        // read the next line ... it is supposed to be the next sequence number
                        // if it is another blank line, keep reading until we reach some actual text
                        while( true ) {
                            s = r.readLine();
                            if (s == null) {
                                break;
                            }

                            if (s.trim().equals("") == false) {
                                // found some text ... break
                                break;
                            }

                            RKLog.Log("SrtParser::parseFileLoose - Unexpected extra blank line at index %d.", e.Index);
                            continue;
                        }

                        if (s == null) {
                            break;
                        }

                        // try to read it as a sequence number
                        try {
                            Long sequence = Long.parseLong(s);
                            if (sequence.equals(sequenceExpected) == true) {
                                // parsed successfully, so continue
                                numberString = s;
                                break;
                            }
                        }
                        catch (Exception ex) {
                            // do nothing
                        }

                        // if we get here, this means we read more text instead of the next sequence number
                        // so just keep going
                        RKLog.Log("SrtParser::parseFileLoose - Unexpected extra text at index %d.", e.Index);
                    }

                    lineString += s + "\n";
                }

                e.Text = lineString;

                entries.add(e);

                if( s == null ) {
                    // EOF reached
                    break;
                }
            }
        }
        catch (Exception ex) {
            RKLog.Log("SrtParser::parseFileLoose exceptioned on %s.", filename);
            RKLog.println(ex.getMessage());
            return null;
        }
        finally {
            Misc.close(r);
        }

        return entries;
    }

    // this is not intended to be a robust parser ... it is intended to throw up on bad SRT's
    public static List<SrtEntry> parseFileStrict(String filename) {
        List<SrtEntry> entries = new ArrayList<SrtEntry>();
        String numberString;
        Long sequenceExpected = 1L;

        FileInputStream fis;
        UnicodeInputStream uis;
        InputStreamReader isr;
        BufferedReader r;

        try {
            fis = new FileInputStream(filename);
            uis = new UnicodeInputStream(fis);
            uis.skipBOM(); // we're just going to skip the BOM all the time and assume it is UTF-8
            isr = new InputStreamReader(uis,"UTF-8");
            r = new BufferedReader(isr);
        }
        catch (Exception ex) {
            return null;
        }

        try {

            while (true) {
                String s;
                String timeString;

                String lineString = "";
                SrtEntry e = new SrtEntry();

                numberString = r.readLine();
                if (numberString == null) {
                    break;
                }

                if (numberString.length() == 0) {
                    continue;
                }

                e.Index = Long.parseLong(numberString);
                if (e.Index.equals(sequenceExpected) == false) {
                    throw new Exception(String.format("Expected sequence number %d, got %d", sequenceExpected, e.Index));
                }

                timeString = r.readLine();
                e.Start = parse(timeString.split("-->")[0]);
                e.End = parse(timeString.split("-->")[1]);

                // this loop reads lines after a sequence number and timestamp
                while (true) {
                    s = r.readLine();
                    if (s == null) {
                        // EOF reached ... this shouldn't happen here
                        break;
                    }

                    // some srt's do a weird thing where they have empty line breaks before actual text.
                    // we may want to let this exception as I don't think that formatting is allowed
                    if (s.trim().equals("") == true) {
                        break;
                    }

                    lineString += s + "\n";
                }

                e.Text = lineString;
                entries.add(e);

                if( s == null ) {
                    RKLog.Log("SrtParser::parseFileStrict - Unexpected EOF at index %d.", e.Index);
                    return null;
                }

                sequenceExpected++;
            }
        }
        catch (Exception ex) {
            RKLog.Log("SrtParser::parseFileStrict exceptioned on %s. (sequence = %d)", filename, sequenceExpected);
            RKLog.println(ex.getMessage());
            return null;
        }
        finally {
            Misc.close(r);
        }

        return entries;
    }
/*
    public static List<SrtEntry> parse(InputStream is) throws IOException {
        LineNumberReader r = new LineNumberReader(new InputStreamReader(is, "UTF-8"));
        List<SrtEntry> entries = new ArrayList<SrtEntry>();
        String numberString;

        while (true) {
            String s;
            SrtEntry e = new SrtEntry();

            numberString = r.readLine();
            if (numberString == null) {
                break;
            }

            String timeString = r.readLine();
            String lineString = "";

            while (true) {
                s = r.readLine();
                if (s == null) {
                    break;
                }

                if (s.trim().equals("") == true) {
                    break;
                }

                lineString += s + "\n";
            }

            e.Index = Long.parseLong(numberString);
            e.Start = parse(timeString.split("-->")[0]);
            e.End = parse(timeString.split("-->")[1]);
            e.Text = lineString;

            entries.add(e);
        }

        return entries;
    }
*/
    private static long parse(String in) {
        long hours = Long.parseLong(in.split(":")[0].trim());
        long minutes = Long.parseLong(in.split(":")[1].trim());
        long seconds = Long.parseLong(in.split(":")[2].split(",")[0].trim());
        long millies = Long.parseLong(in.split(":")[2].split(",")[1].trim());

        return hours * 60 * 60 * 1000 + minutes * 60 * 1000 + seconds * 1000 + millies;
    }
}