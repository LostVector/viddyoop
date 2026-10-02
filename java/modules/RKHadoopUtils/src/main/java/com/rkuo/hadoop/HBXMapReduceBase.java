package com.rkuo.hadoop;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;

import java.util.Map;

public abstract class HBXMapReduceBase extends Configured implements Tool {
    public abstract Map<String,String> LoadSettings(String filename);
    public abstract boolean Submit( Map<String,String> mapSettings );
    public abstract void ExecuteMain(String[] args) throws Exception;
}
