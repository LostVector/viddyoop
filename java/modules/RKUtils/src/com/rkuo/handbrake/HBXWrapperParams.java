package com.rkuo.handbrake;

import com.rkuo.threading.RKEvent;
import com.rkuo.util.CommandLineParser;

public class HBXWrapperParams {

    public String   Source;
    public String   Target;
    public String   Stats;
    public String   TempDirectory;

//    public String   Resources;
    public String   Handbrake_x64;
    public String   MKVInfo;
    public String   MKVExtract;
    public String   SSAConverter;

    // should be optional
    public String   Username;
    public String   Password;
    public String   Hostname;

    // should be optional
    public String   ResourcesUsername;
    public String   ResourcesPassword;
    public String   ResourcesHostname;
    public String   ResourcesLocation;

    public String   Hdfs;
    public String   JobTracker;
    public Integer  JobTrackerPort;

    public String   PrimaryLanguage;
    public String   SecondaryLanguage;

    public RKEvent  Abort;

    public HBXWrapperParams() {
        Source = "";
        Target = "";
        Stats = "";
        TempDirectory = "";

        Handbrake_x64 = "";
        MKVInfo = "";
        MKVExtract = "";
        SSAConverter = "";

        Username = "";
        Password = "";
        Hostname = "";

        ResourcesUsername = "";
        ResourcesPassword = "";
        ResourcesHostname = "";
        ResourcesLocation = "";

        Hdfs = "";
        JobTracker = "";
        JobTrackerPort = 8021;

        PrimaryLanguage = "";
        SecondaryLanguage = "";

        Abort = null;
        return;
    }

    public static HBXWrapperParams GetHBXWrapperParams(CommandLineParser clp) {

        HBXWrapperParams hbxwp;

        hbxwp = new HBXWrapperParams();

        // temp dir ... optional
        hbxwp.TempDirectory = clp.GetString("tempdir");

        // hdfs
        if( clp.Contains("hdfs") == false ) {
            return null;
        }

        hbxwp.Hdfs = clp.GetString("hdfs");

        // jobtracker_hostname
        if( clp.Contains("jobtracker_hostname") == false ) {
            return null;
        }

        hbxwp.JobTracker = clp.GetString("jobtracker_hostname");

        // job tracker
        if( clp.Contains("jobtracker_port") == false ) {
            return null;
        }

        hbxwp.JobTrackerPort = clp.GetInteger("jobtracker_port");

        // resources
        if( clp.Contains("resources_username") == false ) {
            return null;
        }

        hbxwp.ResourcesUsername = clp.GetString("resources_username");

        if( clp.Contains("resources_password") == false ) {
            return null;
        }

        hbxwp.ResourcesPassword = clp.GetString("resources_password");

        if( clp.Contains("resources_hostname") == false ) {
            return null;
        }

        hbxwp.ResourcesHostname = clp.GetString("resources_hostname");

        if( clp.Contains("resources_location") == false ) {
            return null;
        }

        hbxwp.ResourcesLocation = clp.GetString("resources_location");

        // handbrake
        if( clp.Contains("handbrake") == false ) {
            return null;
        }

        hbxwp.Handbrake_x64 = clp.GetString("handbrake");

        // mkvinfo
        if( clp.Contains("mkvinfo") == false ) {
            return null;
        }

        hbxwp.MKVInfo = clp.GetString("mkvinfo");

        // mkvextract
        if( clp.Contains("mkvextract") == false ) {
            return null;
        }

        hbxwp.MKVExtract = clp.GetString("mkvextract");

        // ssaconverter
        if( clp.Contains("ssaconverter") == false ) {
            return null;
        }

        hbxwp.SSAConverter = clp.GetString("ssaconverter");

        // source
        if( clp.Contains("source") == false ) {
            return null;
        }

        hbxwp.Source = clp.GetString("source");

        // target
        if( clp.Contains("target") == false ) {
            return null;
        }

        hbxwp.Target = clp.GetString("target");

        // stats
        if( clp.Contains("stats") == false ) {
            return null;
        }

        hbxwp.Stats = clp.GetString("stats");

        // username
        if( clp.Contains("username") == false ) {
            return null;
        }

        hbxwp.Username = clp.GetString("username");

        // password
        if( clp.Contains("password") == false ) {
            return null;
        }

        hbxwp.Password = clp.GetString("password");

        // hostname
        if( clp.Contains("hostname") == false ) {
            return null;
        }

        hbxwp.Hostname = clp.GetString("hostname");

        // primary_language
        if( clp.Contains("primary_language") == false ) {
            return null;
        }

        hbxwp.PrimaryLanguage = clp.GetString("primary_language");

        // secondary_language
        if( clp.Contains("secondary_language") == false ) {
            return null;
        }

        hbxwp.SecondaryLanguage = clp.GetString("secondary_language");

        return hbxwp;
    }
}
