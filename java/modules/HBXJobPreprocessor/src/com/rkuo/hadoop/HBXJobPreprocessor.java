package com.rkuo.hadoop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.rkuo.handbrake.*;
import com.rkuo.logging.RKLog;
import com.rkuo.mkvtoolnix.MKVExeHelper;
import com.rkuo.mkvtoolnix.MKVTrack;
import com.rkuo.shared.HBXJobPreprocessorBase;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class HBXJobPreprocessor extends HBXJobPreprocessorBase {

    private static long     MIN_ARCHIVAL_DURATION = 75L * 60L * 1000L; // one hour 15 min (in ms)

    protected HBXJobPreprocessorParams params;

    public HBXJobPreprocessor() {
        params = new HBXJobPreprocessorParams();
        return;
    }

    @Override
    public Map<String,String> LoadSettings(String filename) {
        Map<String,String> mapSettings = new HashMap<String,String>();
        XmlMapper xmlMapper = new XmlMapper();

        try {
            mapSettings = xmlMapper.readValue(new File(filename), new TypeReference<HashMap<String,String>>(){});
        }
        catch( IOException ioex ) {
            System.out.println("HBXJobPreprocessor.LoadSettings failed.");
            return mapSettings;
        }

        return mapSettings;
    }

    @Override
    public boolean Configure(Map<String, String> mapSettings) {

        if( mapSettings.containsKey("input_new") == false ) {
            return false;
        }

        if( mapSettings.containsKey("input_reprocess") == false ) {
            return false;
        }

        if( mapSettings.containsKey("output_archive_720") == false ) {
            return false;
        }

        if( mapSettings.containsKey("output_archive_1080") == false ) {
            return false;
        }

        if( mapSettings.containsKey("output_success") == false ) {
            return false;
        }

        if( mapSettings.containsKey("output_failed") == false ) {
            return false;
        }

        if( mapSettings.containsKey("remux") == false ) {
            return false;
        }

        if( mapSettings.containsKey("scratch") == false ) {
            return false;
        }

        if( mapSettings.containsKey("handbrake") == false ) {
            return false;
        }

        if( mapSettings.containsKey("mkvinfo") == false ) {
            return false;
        }

        if( mapSettings.containsKey("mkvextract") == false ) {
            return false;
        }

        if( mapSettings.containsKey("mkvmerge") == false ) {
            return false;
        }

        if( mapSettings.containsKey("aften") == false ) {
            return false;
        }

        if( mapSettings.containsKey("dcadec") == false ) {
            return false;
        }

        if( mapSettings.containsKey("primary_language") == false ) {
            return false;
        }

        if( mapSettings.containsKey("secondary_language") == false ) {
            return false;
        }

        params.InputNew = mapSettings.get("input_new");
        params.InputReprocess = mapSettings.get("input_reprocess");
        params.OutputArchive720 = mapSettings.get("output_archive_720");
        params.OutputArchive1080 = mapSettings.get("output_archive_1080");
        params.OutputSuccess = mapSettings.get("output_success");
        params.OutputFailed = mapSettings.get("output_failed");
        params.Remux = mapSettings.get("remux");
        params.Scratch = mapSettings.get("scratch");
        params.LocalHandBrake = mapSettings.get("handbrake");
        params.LocalMkvInfo = mapSettings.get("mkvinfo");
        params.LocalMkvExtract = mapSettings.get("mkvextract");
        params.LocalMkvMerge = mapSettings.get("mkvmerge");
        params.LocalAften = mapSettings.get("aften");
        params.LocalDcaDec = mapSettings.get("dcadec");
        params.PrimaryLanguage = mapSettings.get("primary_language");
        params.SecondaryLanguage = mapSettings.get("secondary_language");
        return true;
    }

    @Override
    public String[] GetSupportedFileTypes() {
        String[] fileTypes = {".mkv",".avi",".ts",".mov"};
        return fileTypes;
    }

    @Override
    public String Select() {

        ArrayList<String> aNewFilenames, aReprocessFilenames;
//        IJobSubmitter jobSubmitter;

        aNewFilenames = new ArrayList<String>();
        aReprocessFilenames = new ArrayList<String>();

//      jobSubmitter = new XgridJobSubmitter();
//        jobSubmitter = new HadoopJobSubmitter(mr);

        for( String supportedFiletype : GetSupportedFileTypes() ) {
            String[]  newFilenames;

            newFilenames = com.rkuo.io.File.GetFilesWithExtension(new File(params.InputNew), supportedFiletype);
            aNewFilenames.addAll( Arrays.asList(newFilenames) );

            newFilenames = com.rkuo.io.File.GetFilesWithExtension(new File(params.InputReprocess), supportedFiletype);
            aReprocessFilenames.addAll( Arrays.asList(newFilenames) );
        }

        // look for p1 files in the new folder
        for( String filename : aNewFilenames ) {
            if( FileUtils.getAbsolutePathWithoutExtension(filename).endsWith("-p1") == true ) {
                return filename;
            }
        }

        // look for p1 files in the reprocess folder
        for( String filename : aReprocessFilenames ) {
            if( FileUtils.getAbsolutePathWithoutExtension(filename).endsWith("-p1") == true ) {
                return filename;
            }
        }

        // look for any file in the reprocess folder
        if( aReprocessFilenames.size() > 0 ) {
            return aReprocessFilenames.get(0);
        }

        // look for any file in the new folder
        if( aNewFilenames.size() > 0 ) {
            return aNewFilenames.get(0);
        }

        return null;
    }

    @Override
    public Map<String,String> Execute(String selectedFile) {
        Map<String,String> mapResults = new HashMap<String, String>();

        File fSelected = new File(selectedFile); // the input file
        File fRepaired = null; // the normalized form of the file. it may or may not be equal to fSelected.
        File fOutputFailed = new File(params.OutputFailed); // output failed directory
        File fFinal; // the final output file
        HBXScanParams hbxsp;
        Boolean br;

        try {
            // generate repaired version
            MKVTrack[] mkvTracks = null;

            // repair if necessary
            fRepaired = fSelected;
            if( com.rkuo.io.File.GetExtension(fSelected.getAbsolutePath()).compareToIgnoreCase("mkv") == 0 ) {
                if( fSelected.getAbsolutePath().toLowerCase().endsWith(".r.mkv") == false ) {
                    // remux the file
                    File fRemux = new File(FileUtils.PathCombine(params.Remux, FileUtils.getNameWithoutExtension(fSelected.getName()) + ".r.mkv"));
                    File fMkvMerge = new File(params.LocalMkvMerge);
                    int nr = MKVExeHelper.ExecuteMkvMergeRemux(fMkvMerge.getAbsolutePath(), fSelected.getAbsolutePath(), fRemux.getAbsolutePath());
                    if( nr != 0 ) {
                        RKLog.Log("mkvmerge remux failed: %s", fSelected.getAbsolutePath());
                        throw new Exception();
                    }

                    fRepaired = fRemux;
                }
            }

            // operations are carried out on fRepaired moving forward

            // execute mkvinfo on the repaired version, not the original (original muxes often contain bad metadata)
            if( com.rkuo.io.File.GetExtension(fRepaired.getAbsolutePath()).compareToIgnoreCase("mkv") == 0 ) {
                mkvTracks = MKVExeHelper.ExecuteMKVInfo(params.LocalMkvInfo, fRepaired.getAbsolutePath());
                if( mkvTracks == null ) {
                    RKLog.Log("mkvinfo failed: %s", fRepaired.getAbsolutePath());
                    throw new Exception();
                }
            }

            hbxsp = HBXExeHelper.ExecuteHandbrakeScan(params.LocalHandBrake, fRepaired.getAbsolutePath(), true);
            if( hbxsp.Valid == false ) {
                RKLog.Log("HandBrakeScan failed: %s", fRepaired.getAbsolutePath());
                throw new Exception();
            }

            // archive original (optional)
            br = TryArchiving(fSelected, hbxsp, params);
            if( br == false ) {
                RKLog.Log("HandBrakeScan failed: %s", fRepaired.getAbsolutePath());
                throw new Exception();
            }

            // Convert mkv to AC3 version if necessary
            if( mkvTracks != null ) {
                File fAC3;
                AC3ConversionResult ar;

                fAC3 = new File(FileUtils.PathCombine(params.Remux, FileUtils.getNameWithoutExtension(fRepaired.getName()) + "-AC3.r.mkv"));
                ar = TryConvertingToAC3(fRepaired, fAC3, mkvTracks, params);
                if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_HUNG ) {
                    RKLog.Log("AC3 conversion hung: %s", fSelected.getAbsolutePath());
                    throw new Exception();
                }

                if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_FAILED ) {
                    RKLog.Log("AC3 conversion failed: %s", fSelected.getAbsolutePath());
                    throw new Exception();
                }

                if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_OK ) {
                    // We only want to delete fRepaired if it is not the original file
                    if( fSelected.getCanonicalPath().compareTo(fRepaired.getCanonicalPath()) != 0 ) {
                        fRepaired.delete();
                    }

                    fRepaired = fAC3;
                }
            }

            fRepaired = new File( NormalizeFilename(fRepaired.getAbsolutePath()));
            fFinal = new File( FileUtils.PathCombine(params.OutputSuccess, fRepaired.getName()));

            // move to the intermediate source directory for processing
            RKLog.Log("Move for processing: %s", fRepaired.getAbsolutePath());
            br = FileUtils.MoveFile(fRepaired.getAbsolutePath(), fFinal.getAbsolutePath());
            if (br == false) {
                RKLog.Log("Move failed: %s", fRepaired.getAbsolutePath());
                return null;
            }
        }
        catch( Exception ex ) {
            RKLog.Log("Moving %s to failed directory for manual inspection.", fSelected.getAbsolutePath());
            br = FileUtils.MoveFile(fSelected.getAbsolutePath(), fOutputFailed.getAbsolutePath());
            if( br == false ) {
                RKLog.Log("Move failed: %s", fSelected.getAbsolutePath());
            }

            // fRepaired may or may not be equal to fSelected.  By handling fSelected first,
            // we ensure that we set aside fSelected, but also clean up any repaired copies afterwards
            if( fRepaired != null ) {
                fRepaired.delete();
            }

            return null;
        }
        finally {
            // clean up the scratch directory
            File fWorkingDir = new File(params.Scratch);
            if( fWorkingDir.exists() == true ) {
                String[] leftoverFiles = FileUtils.GetFiles(fWorkingDir);
                for( String leftoverFile : leftoverFiles ) {
                    File fLeftover;

                    fLeftover = new File(leftoverFile);
                    fLeftover.delete();
                }
            }
        }

        mapResults.put("duration",Long.toString(hbxsp.Duration));
        mapResults.put("x",Integer.toString(hbxsp.XRes));
        mapResults.put("y",Integer.toString(hbxsp.YRes));
        mapResults.put("output",fFinal.getAbsolutePath());
        return mapResults;
    }

    private File RepairFile(String filename, HBXJobPreprocessorParams params) {
        File fOriginal,fMkvMerge,fRemux,fOutputFailed;
        MKVTrack[]          mkvTracks;
        Boolean br;

        fOriginal = new File(filename);
        fRemux = new File( FileUtils.PathCombine(params.Remux, FileUtils.getNameWithoutExtension(fOriginal.getName()) + ".r.mkv") );
        fMkvMerge = new File(params.LocalMkvMerge);

        fOutputFailed = new File(params.OutputFailed);

        // remux and scan with MKVInfo (if the file is an mkv)
        if( com.rkuo.io.File.GetExtension(fOriginal.getAbsolutePath()).compareToIgnoreCase("mkv") == 0 ) {
            if( fOriginal.getAbsolutePath().toLowerCase().endsWith(".r.mkv") == false ) {
                // remux the file
                int nr = MKVExeHelper.ExecuteMkvMergeRemux(fMkvMerge.getAbsolutePath(), fOriginal.getAbsolutePath(), fRemux.getAbsolutePath());
                if( nr != 0 ) {
                    RKLog.Log("mkvmerge remux failed: %s", fOriginal.getAbsolutePath());
                    RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                    br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                    if (br == false) {
                        RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                    }

                    return null;
                }
            }
            else {
                fRemux = fOriginal;
            }

            // execute mkvinfo on the remux, not the original (original muxes often contain bad metadata)
            mkvTracks = MKVExeHelper.ExecuteMKVInfo(params.LocalMkvInfo, fRemux.getAbsolutePath());
            if( mkvTracks == null ) {
                RKLog.Log("mkvinfo failed: %s", fOriginal.getAbsolutePath());
                RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                if (br == false) {
                    RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                }

                return null;
            }
        }
        else {
            fRemux = fOriginal;
        }

        return fRemux;
    }

    // Archive the file if it is a movie and it is in the input directory (and not the reprocess)
    private boolean TryArchiving(File fSelected, HBXScanParams hbxsp, HBXJobPreprocessorParams params) {
        try {
            File fSourceDir,fArchive720Dir,fArchive1080Dir;

            fSourceDir = new File(params.InputNew);
            fArchive720Dir = new File(params.OutputArchive720);
            fArchive1080Dir = new File(params.OutputArchive1080);

            if( fSelected.getParentFile().getCanonicalPath().compareTo(fSourceDir.getCanonicalPath()) == 0 ) {
                if( hbxsp.Duration > MIN_ARCHIVAL_DURATION ) {
                    ArchiveFile(fSelected, hbxsp, fArchive720Dir, fArchive1080Dir);
                }
            }
        }
        catch( IOException ioex ) {
            RKLog.Log("Directory comparison failed: %s", fSelected.getAbsolutePath());
            return false;
        }

        return true;
    }

    private AC3ConversionResult TryConvertingToAC3( File fSource, File fTarget, MKVTrack[] mkvTracks, HBXJobPreprocessorParams params ) {

        MKVTrack tPreferred;
        File fWorkingDir, fMkvExtract, fMkvMerge, fAften, fDcaDec;

        fWorkingDir = new File(params.Scratch);
        fMkvExtract = new File(params.LocalMkvExtract);
        fMkvMerge = new File(params.LocalMkvMerge);
        fAften = new File(params.LocalAften);
        fDcaDec = new File(params.LocalDcaDec);

        // Select a preferred audio track
        tPreferred = GetPreferredAudioTrack( params.PrimaryLanguage, mkvTracks );
        return TryConvertingToAC3( fSource, fTarget, fWorkingDir, fMkvExtract, fMkvMerge, fAften, fDcaDec, tPreferred );
    }
/*
    private static HBXJobSubmitterState AreDirectoriesValid(HBXJobSubmitterParams hbxjsp, boolean bWarn) {

        HBXJobSubmitterState state;

        state = new HBXJobSubmitterState();
        state.fSourceNewDir = new File(hbxjsp.SourceNew);
        if (state.fSourceNewDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceNew);
            }
            return null;
        }

        state.fSourceReprocessDir = new File(hbxjsp.SourceReprocess);
        if (state.fSourceReprocessDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceReprocess);
            }
            return null;
        }

        state.fRemuxDir = new File(hbxjsp.Remux);
        if (state.fRemuxDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.Remux);
            }
            return null;
        }

        state.fSourceIntermediateDir = new File(hbxjsp.SourceIntermediate);
        if (state.fSourceIntermediateDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceIntermediate);
            }
            return null;
        }

        state.fArchive1080Dir = new File(hbxjsp.SourceArchive1080);
        if (state.fArchive1080Dir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceArchive1080);
            }
            return null;
        }

        state.fArchive720Dir = new File(hbxjsp.SourceArchive720);
        if (state.fArchive720Dir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceArchive720);
            }
            return null;
        }

        state.fSourceFailedDir = new File(hbxjsp.SourceFailed);
        if (state.fSourceFailedDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.SourceFailed);
            }
            return null;
        }

        state.fTargetDir = new File(hbxjsp.Target);
        if (state.fTargetDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.Target);
            }
            return null;
        }

        state.fLogsDir = new File(hbxjsp.Logs);
        if (state.fLogsDir.isDirectory() == false) {
            if (bWarn == true) {
                RKLog.Log("%s is not a directory.", hbxjsp.Logs);
            }
            return null;
        }

        return state;
    }
 */
    /*
    Given a list of watch folders, this function scans through them and picks a file to attempt job submission with.
     */
    /*
    private static boolean TrySubmittingSingleJob(
//            HBXMapReduceBase mr,
            HBXJobSubmitterParams hbxjsp,
            HBXJobSubmitterState state ) {

        ArrayList<String> aNewFilenames, aReprocessFilenames;
//        IJobSubmitter jobSubmitter;

        aNewFilenames = new ArrayList<String>();
        aReprocessFilenames = new ArrayList<String>();

//      jobSubmitter = new XgridJobSubmitter();
//        jobSubmitter = new HadoopJobSubmitter(mr);

        for( String supportedFiletype : HBXConstants.SupportedFiletypes ) {
            String[]  newFilenames;

            newFilenames = com.rkuo.io.File.GetFilesWithExtension(state.fSourceNewDir, supportedFiletype);
            aNewFilenames.addAll( Arrays.asList(newFilenames) );

            newFilenames = com.rkuo.io.File.GetFilesWithExtension(state.fSourceReprocessDir, supportedFiletype);
            aReprocessFilenames.addAll( Arrays.asList(newFilenames) );
        }

        // look for p1 files in the new folder
        for( String filename : aNewFilenames ) {
            if( FileUtils.getAbsolutePathWithoutExtension(filename).endsWith("-p1") == true ) {
                TrySubmitJob( filename, hbxjsp, state, jobSubmitter, true );
                return true;
            }
        }

        // look for p1 files in the reprocess folder
        for( String filename : aReprocessFilenames ) {
            if( FileUtils.getAbsolutePathWithoutExtension(filename).endsWith("-p1") == true ) {
                TrySubmitJob( filename, hbxjsp, state, jobSubmitter, false );
                return true;
            }
        }

        // look for any file in the reprocess folder
        for( String filename : aReprocessFilenames ) {
            TrySubmitJob( filename, hbxjsp, state, jobSubmitter, false );
            return true;
        }

        // look for any file in the new folder
        for( String filename : aNewFilenames ) {
            TrySubmitJob( filename, hbxjsp, state, jobSubmitter, true );
            return true;
        }

        return false;
    }
 */
    /*
    Given a particular file, attempt to submit a job.  Perform exception handling and clean up if needed.
     */
    /*
    public static boolean TrySubmitJob(
            String movieFilename,
            HBXJobSubmitterParams hbxjsp,
            HBXJobSubmitterState state,
            IJobSubmitter jobSubmitter,
            boolean bArchive ) {

        boolean br;

        try {
            br = SubmitJob( movieFilename, hbxjsp, state, jobSubmitter, bArchive );
        }
        catch( Exception ex ) {
            br = false;
        }
        finally {
            // clean up the scratch directory
            File fWorkingDir = new File(hbxjsp.Scratch);
            if( fWorkingDir.exists() == true ) {
                String[] leftoverFiles = FileUtils.GetFiles(fWorkingDir);
                for (String leftoverFile : leftoverFiles) {
                    File fLeftover;

                    fLeftover = new File(leftoverFile);
                    fLeftover.delete();
                }
            }
        }

        return br;
    }
 */

    /*
    Given a file, submits a job.  Files will end up in the processing directory if the job submit succeeds.
     */
    public static String PreprocessFile( String movieFilename, HBXJobPreprocessorParams params ) {

        File                fOriginal, fSourceDir, fRemux, fAC3, fNormalized, fOutputSuccess, fOutputFailed;
        File                fMkvExtract, fMkvMerge, fAften, fDcaDec, fWorkingDir;
//        String              originalStatsFilename;
        String              normalizedSource;
        HBXScanParams       hbxsp;
        MKVTrack[]          mkvTracks;
        MKVTrack            tPreferred;
//        HBXWrapperParams    hbxwp;
        Date now;
        boolean             bArchive = false;

        boolean     br;

        now = new Date();

//        hbxwp = new HBXWrapperParams();
//        hbxwp.Handbrake_x86 = hbxjsp.Handbrake_x86;
//        hbxwp.Handbrake_x64 = params.LocalHandBrake;
//        hbxwp.MKVInfo = hbxjsp.ResourcesMkvInfo;
//        hbxwp.MKVExtract = hbxjsp.ResourcesMkvExtract;
//
//        hbxwp.Username = hbxjsp.Username;
//        hbxwp.Password = hbxjsp.Password;
//        hbxwp.Hostname = hbxjsp.Hostname;
//
//        hbxwp.Hdfs = hbxjsp.Hdfs;
//        hbxwp.JobTracker = hbxjsp.JobTracker;
//        hbxwp.JobTrackerPort = hbxjsp.JobTrackerPort;
//
//        hbxwp.ResourcesUsername = hbxjsp.ResourcesUsername;
//        hbxwp.ResourcesPassword = hbxjsp.ResourcesPassword;
//        hbxwp.ResourcesHostname = hbxjsp.ResourcesHostname;
//        hbxwp.ResourcesLocation = hbxjsp.ResourcesLocation;

//        tPreferred = null;
        mkvTracks = null;

        // construct the intermediate target filenames
        fOriginal = new File(movieFilename);
        fSourceDir = new File(params.InputNew);
        fRemux = new File( FileUtils.PathCombine(params.Remux, FileUtils.getNameWithoutExtension(fOriginal.getName()) + ".r.mkv") );
        fAC3 = new File( FileUtils.PathCombine(params.Remux, FileUtils.getNameWithoutExtension(fOriginal.getName()) + "-AC3.r.mkv") );
        fMkvExtract = new File(params.LocalMkvExtract);
        fMkvMerge = new File(params.LocalMkvMerge);
        fAften = new File(params.LocalAften);
        fDcaDec = new File(params.LocalDcaDec);
        fWorkingDir = new File(params.Scratch);
        normalizedSource = fOriginal.getAbsolutePath();

        File fArchive720Dir = new File(params.OutputArchive720);
        File fArchive1080Dir = new File(params.OutputArchive1080);
        fOutputSuccess = new File(params.OutputSuccess);
        fOutputFailed = new File(params.OutputFailed);

        RKLog.Log("========================================");
        RKLog.Log("Processing %s.", fOriginal.getAbsolutePath());

        // remux and scan with MKVInfo (if the file is an mkv)
        if( com.rkuo.io.File.GetExtension(fOriginal.getAbsolutePath()).compareToIgnoreCase("mkv") == 0 ) {
            if( fOriginal.getAbsolutePath().toLowerCase().endsWith(".r.mkv") == false ) {
                // remux the file
                int nr = MKVExeHelper.ExecuteMkvMergeRemux(fMkvMerge.getAbsolutePath(), fOriginal.getAbsolutePath(), fRemux.getAbsolutePath());
                if( nr != 0 ) {
                    RKLog.Log("mkvmerge remux failed: %s", fOriginal.getAbsolutePath());
                    RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                    br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                    if (br == false) {
                        RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                    }

                    return null;
                }
            }
            else {
                fRemux = fOriginal;
            }

            normalizedSource = fRemux.getAbsolutePath();

            // execute mkvinfo on the remux, not the original (original muxes often contain bad metadata)
            mkvTracks = MKVExeHelper.ExecuteMKVInfo(params.LocalMkvInfo, fRemux.getAbsolutePath());
            if( mkvTracks == null ) {
                RKLog.Log("mkvinfo failed: %s", fOriginal.getAbsolutePath());
                RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                if (br == false) {
                    RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                }

                return null;
            }
        }
        else {
            fRemux = fOriginal;
        }

        // Scan the remux with Handbrake
        hbxsp = HBXExeHelper.ExecuteHandbrakeScan( params.LocalHandBrake, fRemux.getAbsolutePath(), true );
        if( hbxsp.Valid == false ) {
            RKLog.Log("HandBrakeScan failed: %s", fRemux.getAbsolutePath());
            RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
            br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
            if (br == false) {
                RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
            }

            return null;
        }

        // Archive the file if it is a movie and it is in the input directory (and not the reprocess)
        try {
            if( fOriginal.getParentFile().getCanonicalPath().compareTo(fSourceDir.getCanonicalPath()) == 0 ) {
                bArchive = true;
            }
        }
        catch( IOException ioex ) {
            RKLog.Log("Directory comparison failed: %s", fOriginal.getAbsolutePath());
            return null;
        }

        if( bArchive == true ) {
            if( hbxsp.Duration > MIN_ARCHIVAL_DURATION ) {
                ArchiveFile(fOriginal, hbxsp, fArchive720Dir, fArchive1080Dir);
            }
        }

        if( mkvTracks != null ) {
            AC3ConversionResult ar;

            // Select a preferred audio track
            tPreferred = GetPreferredAudioTrack( params.PrimaryLanguage, mkvTracks );

//            ac3Source = TryConvertingToAC3( hbxjsp.MKVDTS2AC3, fOriginal, tPreferred );
            ar = TryConvertingToAC3( fRemux, fAC3, fWorkingDir, fMkvExtract, fMkvMerge, fAften, fDcaDec, tPreferred );
            if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_OK ) {
                // converted successfully
                // remove the remux file
                normalizedSource = ar.convertedFile;
                fRemux.delete();
            }
            else if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_HUNG ) {
                // tried to convert, but hung
                RKLog.Log("AC3 conversion hung: %s", fOriginal.getAbsolutePath());
                RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                if (br == false) {
                    RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                }

                fRemux.delete();
                return null;
            }
            else if( ar.returnCode == AC3ConversionResult.AC3CONVERSION_FAILED ) {
                RKLog.Log("AC3 conversion failed.");
                RKLog.Log("Moving %s to failed directory for manual inspection.", fOriginal.getAbsolutePath());
                br = FileUtils.MoveFile(fOriginal.getAbsolutePath(), fOutputFailed.getAbsolutePath());
                if (br == false) {
                    RKLog.Log("Move failed: %s", fOriginal.getAbsolutePath());
                }

                fRemux.delete();
                return null;
            }
            else {
                // conversion was not necessary
            }
        }

        // since the ac3 script messes with the file name, we change it back if needed
        // only affects mkv
        normalizedSource = NormalizeFilename( normalizedSource );

        fNormalized = new File(normalizedSource);
//        hbxwp.Source = FileUtils.PathCombine(state.fSourceIntermediateDir.getAbsolutePath(), fNormalized.getName());
//        hbxwp.Target = FileUtils.PathCombine(state.fTargetDir.getAbsolutePath(), FileUtils.getNameWithoutExtension(fNormalized.getName()) + ".m4v");
//        hbxwp.Stats = FileUtils.PathCombine(state.fLogsDir.getAbsolutePath(), fNormalized.getName() + ".encodingstats.xml");
        fOutputSuccess = new File( FileUtils.PathCombine(fOutputSuccess.getAbsolutePath(), fNormalized.getName()));
//        originalStatsFilename = FileUtils.PathCombine(state.fLogsDir.getAbsolutePath(), fNormalized.getName() + ".originalstats.xml");

        // move to the intermediate source directory for processing
        RKLog.Log("Move for processing: %s", normalizedSource);
        br = FileUtils.MoveFile(normalizedSource, fOutputSuccess.getAbsolutePath());
        if (br == false) {
            RKLog.Log("Move failed: %s", normalizedSource);
            return null;
        }

//        br = jobSubmitter.Submit(hbxwp);
//        if( br == false ) {
//            RKLog.Log( "Job submission failed (%s).", hbxwp.Source );
//            return false;
//        }

//        if( fOriginal.getAbsolutePath().compareToIgnoreCase(fNormalized.getAbsolutePath()) == 0 ) {
//            WriteOriginalStats( now, fIntermediate, hbxsp, false, originalStatsFilename );
//        }
//        else {
//            WriteOriginalStats( now, fIntermediate, hbxsp, true, originalStatsFilename );
//        }

//        fOriginal.delete();
        return fOutputSuccess.getAbsolutePath();
    }

    private static void ArchiveFile( File fOriginal, HBXScanParams hbxsp, File fArchive720Dir, File fArchive1080Dir ) {

        String  finishedFileName;
        boolean br;

        if( hbxsp.XRes > 1280 || hbxsp.YRes > 720 ) {
            finishedFileName = FileUtils.PathCombine(fArchive1080Dir.getAbsolutePath(), fOriginal.getName());
        }
        else {
            finishedFileName = FileUtils.PathCombine(fArchive720Dir.getAbsolutePath(), fOriginal.getName());
        }

        RKLog.Log( "Duration of %s is %s. Looks like a movie ... copy to archival folder.", fOriginal.getName(), Misc.GetTimeString(hbxsp.Duration) );
        br = FileUtils.Copy( fOriginal.getAbsolutePath(), finishedFileName + ".tmp" );
        if( br == false ) {
            RKLog.Log( "Failed to copy %s.", fOriginal.getAbsolutePath() );
        }
        else {
            RKLog.Log( "Copied %s.", fOriginal.getAbsolutePath() );
        }

        br = FileUtils.MoveFile( finishedFileName +".tmp", finishedFileName );
        if( br == false ) {
            RKLog.Log( "Failed to move %s to %s.", finishedFileName + ".tmp", finishedFileName );
        }
        else {
            // not really necessary to log this
            // RKLog.Log( "Copied %s.", f.getAbsolutePath() );
        }

        return;
    }

    /*
    Will attempt to return the preferred language. If preferred language is unknown or not available,
    the best track is returned. (usually in the case of foreign films)
     */
    private static MKVTrack GetPreferredAudioTrack( String language, MKVTrack[] mkvTracks ) {

        MKVTrack    tPreferred;

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_EAC3", language, 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English E-AC3 5.1 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English E-AC3 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_EAC3", language, 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English E-AC3 5.0 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English E-AC3 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_AC3", language, 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English AC3 5.1 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English AC3 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_AC3", language, 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English AC3 5.0 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English AC3 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", language, 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English DTS 5.1 track. Will convert to AC3." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English DTS 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", language, 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English DTS 5.0 track. Will convert to AC3." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English DTS 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", language, 8 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an English DTS 7.1 track. Will convert to AC3." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find an English DTS 7.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_EAC3", "", 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language E-AC3 5.1 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any E-AC3 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_EAC3", "", 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language E-AC3 5.0 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any E-AC3 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_AC3", "", 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language AC3 5.1 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any AC3 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_AC3", "", 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language AC3 5.0 track. Will encode the original file." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any AC3 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", "", 6 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language DTS 5.1 track. Will convert to AC3." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any DTS 5.1 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", "", 5 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language DTS 5.0 track. Will convert to AC3." );
            return tPreferred;
        }
        RKLog.Log( "Couldn't find any DTS 5.0 track." );

        tPreferred = MKVTrack.FindMKVAudioTrack( mkvTracks, "A_DTS", "", 8 );
        if( tPreferred != null ) {
            RKLog.Log( "Found an unknown language DTS 7.1 track. Will convert to AC3." );
            return tPreferred;
        }

        RKLog.Log( "Couldn't find any DTS 7.1 track." );

        RKLog.Log( "Couldn't find any preferred audio tracks. Encoder will use first available track." );

        return null;
    }

    private static AC3ConversionResult TryConvertingToAC3( File fOriginal, File fAC3, File fWorkingDir,
                                                           File fMkvExtract, File fMkvMerge,
                                                           File fAften, File fDcaDec, MKVTrack tPreferred ) {

        AC3ConversionResult ar;

        ar = new AC3ConversionResult();

        // if necessary, convert the preferred audio track to AC3
        if( tPreferred == null ) {
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_UNNECESSARY;
            return ar;
        }

        if (tPreferred.CodecID.compareToIgnoreCase("A_DTS") != 0) {
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_UNNECESSARY;
            return ar;
        }

        if( fOriginal.getAbsolutePath().endsWith("-AC3.mkv") == true ) {
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_UNNECESSARY;
            return ar;
        }

        if( fOriginal.getAbsolutePath().endsWith("-AC3.r.mkv") == true ) {
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_UNNECESSARY;
            return ar;
        }

        int exitCode;
//        String ac3FileName;
//        String sBase;
//        File fAC3;
        String[] leftoverFiles;

//        sBase = FileUtils.getNameWithoutExtension(fOriginal.getAbsolutePath());
//        ac3FileName = FileUtils.PathCombine(fOriginal.getParent(), sBase + "-AC3.mkv");
//        fAC3 = new File(fAC3);
        fWorkingDir.mkdir();

        leftoverFiles = FileUtils.GetFiles(fWorkingDir);
        for (String leftoverFile : leftoverFiles) {
            File fLeftover;

            fLeftover = new File(leftoverFile);
            fLeftover.delete();
        }

        RKLog.Log("Generating an mkv with an AC3 track (%s).", fAC3.getAbsolutePath());
//        exitCode = HBXExeHelper.ExecuteMKVDTS2AC3(conversionScript, fWorkingDir.getAbsolutePath(), fOriginal.getAbsolutePath(), tPreferred.TrackId);
        exitCode = MKVExeHelper.ConvertDTSToAC3(
                fOriginal.getAbsolutePath(), fAC3.getAbsolutePath(),
                fMkvExtract.getAbsolutePath(), fMkvMerge.getAbsolutePath(),
                fAften.getAbsolutePath(), fDcaDec.getAbsolutePath(),
                fWorkingDir.getAbsolutePath(), tPreferred);
        if( exitCode == Integer.MIN_VALUE ) {
            // thsi value means the conversion progress hung
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_HUNG;
            return ar;
        }
        else if( exitCode != 0 ) {
            // it failed ... we need to just use the original
            RKLog.Log("AC3 mkv generation failed. Falling back to encoding from the original file.");
            fAC3.delete();
            ar.returnCode = AC3ConversionResult.AC3CONVERSION_FAILED;
            return ar;
        }
        else {
            // success ... delete the original and use the AC3 version
            RKLog.Log("AC3 mkv generation succeeded. Will encode from the AC3 mkv.");
        }

        ar.returnCode = AC3ConversionResult.AC3CONVERSION_OK;
        ar.convertedFile = fAC3.getAbsolutePath();
        return ar;
    }

    /*
    Accepts an absolute path and fixes up the suffix to match our normal naming conventions
     */
    private static String NormalizeFilename( String normalizedSourceIn ) {

        String  p1ac3Suffix;
        String  normalizedSource;

        normalizedSource = normalizedSourceIn;

        normalizedSource = NormalizeExtension(normalizedSource,"-p1-AC3.mkv","-AC3-p1.mkv");
        normalizedSource = NormalizeExtension(normalizedSource,"-p1-AC3.r.mkv","-AC3-p1.r.mkv");

        // if this is a high priority file, we put AAA- on the front so that it shows up at the
        // top of the apple tv menu
        if( normalizedSource.endsWith("-p1.mkv") == true ) {
            File    f;
            String  newSource;

            f = new File(normalizedSource);
            newSource = FileUtils.PathCombine( f.getParent(), "AAA-" + f.getName() );
            f.renameTo( new File(newSource) );
            normalizedSource = newSource;
        }

        return normalizedSource;
    }

    private static String NormalizeExtension( String normalizedSource, String originalExtension, String newExtension ) {

        originalExtension = "-p1-AC3.mkv";
        if( normalizedSource.endsWith(originalExtension) == true ) {
            String  root;
            File    f;
            String  newSource;

            f = new File(normalizedSource);

            root = normalizedSource.substring(0, normalizedSource.length() - originalExtension.length());
            newSource = root + newExtension;
            f.renameTo( new File(newSource) );
            normalizedSource = newSource;
        }

        return normalizedSource;
    }
}
