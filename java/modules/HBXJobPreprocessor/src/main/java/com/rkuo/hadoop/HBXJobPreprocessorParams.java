package com.rkuo.hadoop;

public class HBXJobPreprocessorParams {

    public String InputNew;
    public String InputReprocess;

    public String OutputArchive720;
    public String OutputArchive1080;
    public String OutputSuccess;
    public String OutputFailed;

//    public String   Logs;

    public String   Remux;
    public String   Scratch;

    public String   LocalHandBrake;
    public String   LocalMkvInfo;
    public String   LocalMkvExtract;
    public String   LocalMkvMerge;
    public String   LocalAften;
    public String   LocalDcaDec;

    public String   PrimaryLanguage;
    public String   SecondaryLanguage;

    public HBXJobPreprocessorParams() {

        InputNew = "";
        InputReprocess = "";

        OutputArchive720 = "";
        OutputArchive1080 = "";

        OutputSuccess = "";
        OutputFailed = "";

        Remux = "";
        Scratch = "";

        LocalHandBrake = "";
        LocalMkvInfo = "";
        LocalMkvExtract = "";
        LocalMkvMerge = "";
        LocalAften = "";
        LocalDcaDec = "";

        PrimaryLanguage = "";
        SecondaryLanguage = "";
        return;
    }
}
