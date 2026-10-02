package com.rkuo.Executables;

import com.rkuo.shared.HBXImporterParams;
import com.rkuo.logging.RKLog;
import com.rkuo.net.ssh.Scp;
import com.rkuo.net.ssh.Sftp;
import com.rkuo.util.CommandLineParser;
import com.rkuo.util.FileUtils;
import com.rkuo.util.ShutdownThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/21/12
 * Time: 12:26 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXSSHFileBrokerExe {

    private static long SCAN_DELAY_MS = 5 * 1000; // 5 seconds
    private static long EVENT_LOOP_DELAY_MS = 1000;
    private static long WARNING_DELAY = 60 * 60 * 1000;
    private static long ONE_GB = 1024L * 1024L * 1024L;

    public static void main( String[] args ) {

        CommandLineParser clp;
        HBXImporterParams hbxip;
        ShutdownThread shutdownThread;
        boolean             bMinimumDiskSpaceActive;
        long                lastRun, lastWarning;
        long                elapsed;

        lastRun = Long.MIN_VALUE;
        lastWarning = Long.MAX_VALUE;
        bMinimumDiskSpaceActive = false;

        try {
            shutdownThread = new ShutdownThread();
            Runtime.getRuntime().addShutdownHook( shutdownThread );
        }
        catch( Exception ex ) {
            RKLog.Log("Could not add shutdown hook.");
            return;
        }

        clp = new CommandLineParser();

        clp.Parse(args);

        hbxip = GetHBXImporterParams( clp );
        if( hbxip == null ) {
            RKLog.Log( "Missing required parameters." );
            return;
        }

        RKLog.Log( "Source: %s", hbxip.Source );
        RKLog.Log( "Target: %s", hbxip.Target );

        {
        String logLine = "File Extensions: ";
        for( String s : hbxip.FileExtensions ) {
            logLine += s + ",";
        }

        // remove the trailing comma
        logLine = logLine.substring(0, logLine.length() - 1 );
        RKLog.Log( logLine );
        }

        if( hbxip.MinimumTargetDiskSpace != -1 ) {
            RKLog.Log( "Minimum target disk space: %d", hbxip.MinimumTargetDiskSpace );
        }
        if( hbxip.FinalTarget.length() > 0 ) {
            RKLog.Log( "FinalTarget: %s", hbxip.FinalTarget );
        }

        elapsed = SCAN_DELAY_MS;
        while( true ) {
            File fSourceDir;
            boolean     bLogWarnings;
            boolean     br;

            Scp scp = new Scp( hbxip.Username, hbxip.Password, hbxip.Hostname );
            Sftp sftp = new Sftp( hbxip.Username, hbxip.Password, hbxip.Hostname );
            long now = System.currentTimeMillis();

            bLogWarnings = false;
            if( lastWarning - now > WARNING_DELAY ) {
                bLogWarnings = true;
            }

            synchronized( shutdownThread ) {
                if( shutdownThread.isShutdown() == true ) {
                    break;
                }
            }

            try {
                Thread.currentThread().sleep( EVENT_LOOP_DELAY_MS );
            }
            catch( InterruptedException iex ) {
                // do nothing?
                break;
            }

            elapsed += EVENT_LOOP_DELAY_MS;
            if( elapsed < SCAN_DELAY_MS ) {
                lastRun = now;
                continue;
            }

            elapsed = 0;

            fSourceDir = new File( hbxip.Source );
            if( fSourceDir.isDirectory() == false ) {
                if( bLogWarnings == true ) {
                    RKLog.Log( "%s is not a directory.", hbxip.Source );
                    lastWarning = now;
                }
                lastRun = now;
                continue;
            }
            
            ArrayList<String> allFilenames = new ArrayList<String>();
            for( String extension : hbxip.FileExtensions ) {
                String[]    fileNames;

                fileNames = com.rkuo.io.File.GetFilesWithExtension( fSourceDir, "." + extension );
                Collections.addAll(allFilenames, fileNames);
            }

            if( allFilenames.size() == 0 ) {
                lastRun = now;
                continue;
            }

            // there is some overhead to this, so we only check the target directories if there is something we need to broker over
            br = sftp.SftpIsDirectory( hbxip.Target );
            if( br == false ) {
                if( bLogWarnings == true ) {
                    RKLog.Log( "%s is not a directory.", hbxip.Target );
                    lastWarning = now;
                }
                lastRun = now;
                continue;
            }

            if( hbxip.FinalTarget.length() > 0 ) {
                br = sftp.SftpIsDirectory( hbxip.FinalTarget );
                if( br == false ) {
                    if( bLogWarnings == true ) {
                        RKLog.Log( "%s is not a directory.", hbxip.FinalTarget );
                        lastWarning = now;
                    }
                    lastRun = now;
                    continue;
                }
            }

            for( String sourceName : allFilenames ) {
                File    f;
                f = new File( sourceName );

                // do not copy if the target's free disk space is below a specified threshold
                // but always copy srt's, those are super tiny
                if( com.rkuo.io.File.GetExtension(sourceName).compareToIgnoreCase("srt") != 0 ) {
                    if( hbxip.MinimumTargetDiskSpace != -1 ) {
                        long    lUsableSpace;
                        long    lMinimumAfterCopySpace;

                        lUsableSpace = scp.GetFreeDiskSpace(hbxip.Target);
                        lMinimumAfterCopySpace = hbxip.MinimumTargetDiskSpace + f.length();
                        if( lUsableSpace < lMinimumAfterCopySpace ) {
                            if( bMinimumDiskSpaceActive == false ) {
                                RKLog.Log( "Minimum target disk space is %d GB. Free space after copy would be %d GB.",
                                        hbxip.MinimumTargetDiskSpace / ONE_GB, (lUsableSpace - f.length()) / ONE_GB);
                            }

                            bMinimumDiskSpaceActive = true;
                            break;
                        }

                        bMinimumDiskSpaceActive = false;
                    }
                }

                // for each mkv in the source directory, copy to the target with tmp extension
                // rename to mkv if successful and delete the source
                BrokerFile(sourceName, hbxip, scp, sftp);
            }

            lastRun = now;
        }

        System.out.println( "Exiting..." );
        return;
    }

    private static HBXImporterParams GetHBXImporterParams(CommandLineParser clp) {

        HBXImporterParams   hbxip;
        String              value;
        String[]              extensions;

        hbxip = new HBXImporterParams();


        if( clp.Contains("username") == false ) {
            return null;
        }
        hbxip.Username = clp.GetString("username");

        if( clp.Contains("password") == false ) {
            return null;
        }
        hbxip.Password = clp.GetString("password");

        if( clp.Contains("hostname") == false ) {
            return null;
        }
        hbxip.Hostname = clp.GetString("hostname");

        if( clp.Contains("source") == false ) {
            return null;
        }
        hbxip.Source = clp.GetString("source");

        if( clp.Contains("target") == false ) {
            return null;
        }
        hbxip.Target = clp.GetString("target");

        if( clp.Contains("fileextension") == false ) {
            return null;
        }

//        hbxip.FileExtension = clp.GetString("fileextension");

        value = clp.GetString("fileextension");
        extensions = value.split(",");
        for( String s : extensions ) {
            hbxip.FileExtensions.add( s );
        }

        // Optional
        hbxip.FinalTarget = clp.GetString("finaltarget");

        // this value is read in GB ... eg 96 = 96GB
        value = clp.GetString("minimumtargetdiskspace");
        if( value.length() > 0 ) {
            hbxip.MinimumTargetDiskSpace = Long.parseLong( value );

            // convert to GB
            hbxip.MinimumTargetDiskSpace *= (1024L * 1024L * 1024L);
        }

        hbxip.RenameForIFlicks = clp.GetBoolean("iflicks");

        return hbxip;
    }

    private static String GenerateIFlicksName( String fileName ) {

        String  baseName, tempName;
        String  sParent;
        String  sFileExtension;
        String  sYear;
        String  ifBaseName, ifFullName;
        int     nYear;
        int     nOpen, nClose;

        sFileExtension = com.rkuo.io.File.GetExtension( fileName );
        if( sFileExtension == null ) {
            return null;
        }

        sParent = new File( fileName ).getParent();
        if( sParent == null ) {
            return null;
        }

        baseName = FileUtils.getNameWithoutExtension( fileName );
        if( baseName == null ) {
            return null;
        }

        nOpen = baseName.lastIndexOf( '(' );
        if( nOpen == -1 ) {
            return null;
        }

        nClose = baseName.lastIndexOf( ')' );
        if( nClose == -1 ) {
            return null;
        }

        if( nOpen > nClose ) {
            return null;
        }

        tempName = baseName.substring(0, nOpen);
        tempName = tempName.trim();

        nOpen = tempName.lastIndexOf( '(' );
        if( nOpen == -1 ) {
            return null;
        }

        nClose = tempName.lastIndexOf( ')' );
        if( nClose == -1 ) {
            return null;
        }

        if( nOpen+1 >= nClose ) {
            return null;
        }

        sYear = tempName.substring(nOpen+1,nClose);

        try {
            nYear = Integer.parseInt(sYear);
        }
        catch( Exception ex ) {
            return null;
        }

        if( nYear < 1888 || nYear > 2100 ) {
            return null;
        }

        tempName = tempName.substring(0,nOpen);
        tempName = tempName.trim();

        ifBaseName = String.format( "%s.(%d).%s", tempName, nYear, sFileExtension );
        ifFullName = FileUtils.PathCombine( sParent, ifBaseName );
        return ifFullName;
    }

    protected static void BrokerFile(String sourceName, HBXImporterParams hbxip, Scp scp, Sftp sftp) {

        boolean br;

        File f;
        File fTempTarget;
        String tempTargetName, targetName, finalTargetName;

        File fTarget;
        long lOriginalLength;

        f = new File(sourceName);

        // Copy file with a .tmp extension ... renaming to the real filename only at the very end
        // this maintains atomicity
        tempTargetName = FileUtils.PathCombine(hbxip.Target, f.getName() + ".tmp");
        fTempTarget = new File(tempTargetName);
        targetName = FileUtils.PathCombine(hbxip.Target, f.getName());
        if( hbxip.RenameForIFlicks == true ) {
            String iFlicksName;
            iFlicksName = GenerateIFlicksName(targetName);
            if( iFlicksName != null ) {
                targetName = iFlicksName;
            }
        }

        fTarget = new File(targetName);

        lOriginalLength = f.length();
        RKLog.Log("Moving %s. Source length is %d.", f.getName(), lOriginalLength);

        br = scp.ScpTo(f.getAbsolutePath(), fTempTarget.getAbsolutePath(), null);
        if( br == false ) {
            RKLog.Log("FileUtils.Copy failed: %s to %s.", f.getAbsolutePath(), fTempTarget.getAbsolutePath());
            return;
        }

        long lTempTargetLength = sftp.SftpGetFileSize(fTempTarget.getAbsolutePath());
        if( lOriginalLength != lTempTargetLength ) {
            RKLog.Log("FileUtils.Copy failed: source length was %d, target length is %d.", lOriginalLength, fTempTarget.length());
            sftp.SftpDelete(fTempTarget.getAbsolutePath()); // we don't care if this fails
            return;
        }

        br = sftp.SftpDelete(targetName);
        br = sftp.SftpRename(fTempTarget.getAbsolutePath(), targetName);
        if( br == false ) {
            RKLog.Log("Rename from %s to %s failed.", fTempTarget.getAbsolutePath(), targetName);
            return;
        }

        // If we have a secondary/final directory to move the file to after the copy, we do it now
        if( hbxip.FinalTarget.length() > 0 ) {
            String finalTargetDirectory;

            finalTargetDirectory = hbxip.FinalTarget;
            finalTargetName = FileUtils.PathCombine(finalTargetDirectory, fTarget.getName());
            br = sftp.SftpDelete(finalTargetName);
            br = sftp.SftpRename(targetName, finalTargetName);
            if( br == false ) {
                RKLog.Log("Rename from %s to %s failed.", targetName, finalTargetName);
                return;
            }
        }

        // finally, delete the source
        br = f.delete();
        if( br == false ) {
            RKLog.Log("Failed to delete %s.", f.getAbsolutePath());
            return;
        }

        if( hbxip.FinalTarget.length() > 0 ) {
            RKLog.Log("Move succeeded: %s", f.getName());
        } else {
            RKLog.Log("Move failed: %s", f.getName());
        }

        return;
    }
}
