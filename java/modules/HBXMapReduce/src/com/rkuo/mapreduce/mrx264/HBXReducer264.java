package com.rkuo.mapreduce.mrx264;

import com.rkuo.Executables.HBXHadoopWrapperLogic;
import com.rkuo.handbrake.HBXWrapperParams;
import com.rkuo.handbrake.IHBXExecutor;
import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.mapreduce.mrv2.HBXHadoopHandBrakeExeCallback2;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.File;
import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/17/14
 * Time: 8:34 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXReducer264 extends Reducer<LongWritable,Text,LongWritable,Text> {

    protected String Username;
    protected String Password;
    protected String Hostname;

    protected String ResourcesUsername;
    protected String ResourcesPassword;
    protected String ResourcesHostname;
    protected String ResourcesLocation;

    protected String Source;
    protected String Target;
    protected String Stats;

    protected String HandBrake;
    protected String MKVInfo;
    protected String MKVExtract;
    protected String SSAConverter;

    @Override
    protected void setup(Reducer.Context context) throws IOException, InterruptedException {
        Configuration c = context.getConfiguration();

        Username = c.get("username");
        Password = c.get("password");
        Hostname = c.get("hostname");

        ResourcesUsername = c.get("resources_username");
        ResourcesPassword = c.get("resources_password");
        ResourcesHostname = c.get("resources_hostname");
        ResourcesLocation = c.get("resources_location");

        Source = c.get("source");
        Target = c.get("target");
        Stats = c.get("stats");

        HandBrake = c.get("handbrake");
        MKVInfo = c.get("mkvinfo");
        MKVExtract = c.get("mkvextract");
        SSAConverter = c.get("ssaconverter");
        return;
    }

    @Override
	public void reduce(LongWritable key, Iterable<Text> values, Reducer.Context context) throws IOException, InterruptedException {

        boolean br;

        File d = new File(".");

        System.out.format("HBXReducer::reduce started\n");

//        while( values.hasNext() == true ) {
//            Text val = values.next();
//            output.collect(key, val);
//        }
/*
        Configuration config = new Configuration();
        FileSystem hdfs = FileSystem.get(config);
        File d = new File(".");

        System.out.println("Current directory's canonical path: " + d.getCanonicalPath());
        Path src = new Path("/user/root/handbrake/HandBrakeCLI-0.9.8");
        Path dst = new Path(d.getCanonicalPath() + Path.SEPARATOR_CHAR + "HandBrakeCLI-0.9.8");

        PrintFiles(d.getCanonicalPath());
        hdfs.copyToLocalFile(false, src, dst);
        PrintFiles(d.getCanonicalPath());
        */

        HBXWrapperParams hbxwp;

        hbxwp = new HBXWrapperParams();
        hbxwp.Username = Username;
        hbxwp.Password = Password;
        hbxwp.Hostname = Hostname;

        hbxwp.Source = Source;
        hbxwp.Target = Target;
        hbxwp.Stats = Stats;

        hbxwp.ResourcesUsername = ResourcesUsername;
        hbxwp.ResourcesPassword = ResourcesPassword;
        hbxwp.ResourcesHostname = ResourcesHostname;
        hbxwp.ResourcesLocation = ResourcesLocation;

        hbxwp.Handbrake_x64 = HandBrake;
        hbxwp.MKVInfo = MKVInfo;
        hbxwp.MKVExtract = MKVExtract;
        hbxwp.SSAConverter = SSAConverter;

        hbxwp.TempDirectory = d.getCanonicalPath();
//            hbxwp.TempDirectory = "";

        IHBXExecutor executor;
        IHandBrakeExeCallback callback;

        callback = new HBXHadoopHandBrakeExeCallback2(context);

        executor = new HBXHadoopWrapperLogic(callback);

        br = executor.Execute( hbxwp );
        if( br == false ) {
            throw new IOException("HBXHadoopWrapperLogic::Execute failed.");
        }

//          HBXExeHelper.ExecuteHandbrake095ForAppleTV2012();

        System.out.format("HBXReducer::reduce finished\n");
	}
}
