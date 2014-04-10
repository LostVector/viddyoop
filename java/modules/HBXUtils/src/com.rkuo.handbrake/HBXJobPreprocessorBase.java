package com.rkuo.handbrake;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 3/28/14
 * Time: 1:10 PM
 * To change this template use File | Settings | File Templates.
 */
public abstract class HBXJobPreprocessorBase {
    public abstract Map<String,String> LoadSettings(String filename);
    public abstract boolean Configure( Map<String,String> mapSettings );
    public abstract String[] GetSupportedFileTypes();
    public abstract String Select();
    public abstract Map<String,String> Execute(String selectedFile);
}
