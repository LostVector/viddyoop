package com.rkuo.mapreduce.mrv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.rkuo.hadoop.HBXMapReduceBase;
import com.rkuo.handbrake.HBXWrapperParams;
import com.rkuo.util.CommandLineParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ToolRunner;

import java.io.File;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.text.SimpleDateFormat;
import java.util.*;

public class HBXMapReduce2 extends HBXMapReduceBase {

//    protected final static String HADOOP_USER = "vidoop";

    @Override
    public Map<String,String> LoadSettings(String filename) {
        Map<String,String> mapSettings = new HashMap<String,String>();
        XmlMapper xmlMapper = new XmlMapper();

        try {
            mapSettings = xmlMapper.readValue(new File(filename), new TypeReference<HashMap<String,String>>(){});
        }
        catch( IOException ioex ) {
            System.out.println("HBXMapReduce2.LoadSettings failed.");
            return mapSettings;
        }

        return mapSettings;
    }

    public int run(String[] args) throws Exception {
        HBXWrapperParams hbxwp;
        final String hdfsUrl;
        String inputPath, outputPath;
        File f;
        final String HADOOP_USER = System.getenv("HADOOP_USER_NAME");

        System.out.format("HBXMapReduce2::run begins.\n");

        Configuration c = getConf();

        CommandLineParser clp = new CommandLineParser();

        clp.Parse(args);

        hbxwp = HBXWrapperParams.GetHBXWrapperParams(clp);
        if( hbxwp == null ) {
            return 1;
        }

        hdfsUrl = hbxwp.Hdfs;

        // input
//        if( clp.Contains("input") == false ) {
//            return 1;
//        }
//
//        inputPath = clp.GetString("input");

        // output
//        if( clp.Contains("output") == false ) {
//            return 1;
//        }
//
//        outputPath = clp.GetString("output");

        f = new File(hbxwp.Source);

        // Add resources
        //		conf.addResource("hdfs-default.xml");
        //		conf.addResource("hdfs-site.xml");
        //		conf.addResource("mapred-default.xml");
        //		conf.addResource("mapred-site.xml");

        // all configuration parameters must be updated before sending it into the job constructor
        updateConfiguration(c, hbxwp);

        UserGroupInformation ugi = UserGroupInformation.createRemoteUser("hdfs");
        try {
            ugi.doAs(new PrivilegedExceptionAction<Void>() {

                public Void run() throws Exception {

                    Configuration conf = new Configuration();
                    conf.set("fs.defaultFS", hdfsUrl);
                    conf.set("hadoop.job.ugi", "hdfs");

                    FileSystem fs = FileSystem.get(conf);

                    // will not error if path exists
                    fs.mkdirs(new Path(String.format("/user/%s/input",HADOOP_USER)));
                    fs.mkdirs(new Path(String.format("/user/%s/output",HADOOP_USER)));

                    fs.setOwner(new Path(String.format("/user/%s",HADOOP_USER)),HADOOP_USER,HADOOP_USER);
                    fs.setOwner(new Path(String.format("/user/%s/input",HADOOP_USER)),HADOOP_USER,HADOOP_USER);
                    fs.setOwner(new Path(String.format("/user/%s/output",HADOOP_USER)),HADOOP_USER,HADOOP_USER);
                    return null;
                }
            });
        }
        catch( Exception e ) {
            e.printStackTrace();
        }

        // HDFS API
        File fTemp;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
        String sDate = sdf.format(new Date());

        FileSystem fs = null;

        try {
            // generate a dummy input file. more advanced usages might want to actually pass in a file instead
            // of just generating it here
            fs = FileSystem.get(c);

            // input path is a file
            inputPath = String.format("/user/%s/input/%s-%s.txt",HADOOP_USER,f.getName(),sDate);
            fTemp = File.createTempFile("input",".txt");
            com.rkuo.io.File.WriteString("This is a dummy file for Viddyoop.\n\n", fTemp);
            fs.copyFromLocalFile(new Path(fTemp.getAbsolutePath()), new Path(inputPath));
            fTemp.delete();
        }
        finally {
            if( fs != null ) {
                fs.close();
            }
        }

        // output path is a directory
        outputPath = f.getName() + "-" + sDate;

        // finally, create the job
        Job job = new Job(c);
        job.setJobName(f.getName());

        // these must correspond to the output key/value templates of the mapper class
        // and the inputs of the reducer
        job.setMapOutputKeyClass(LongWritable.class);
        job.setMapOutputValueClass(Text.class);

        // these must correspond to the output key/value templates of the reducer class
        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(Text.class);

        // mapper and reducer defined here
        job.setMapperClass(HBXMapper.class);
//        job.setCombinerClass(HBXReducer.class); // not used
        job.setReducerClass(HBXReducer.class);

        // TextInputFormat generates inputs of LongWritable (character position), Text (each line).  See docs.
        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);
        TextInputFormat.setInputPaths(job, new Path(inputPath));
        TextOutputFormat.setOutputPath(job, new Path(outputPath));

        // this setJarByClass call is critical.  I don't know why the new JobConf call above doesn't handle this.
        job.setJarByClass(HBXMapper.class);
        System.out.format("getJar() - %s\n", job.getJar());

        // submit is non-blocking
        job.submit();
        System.out.format("Job submitted for %s.\n", f.getName());

        return 0;
    }

    protected void updateConfiguration( Configuration c, HBXWrapperParams hbxwp ) {

        c.set("fs.defaultFS", hbxwp.Hdfs);
        c.set("mapred.job.tracker", hbxwp.JobTracker + ":" + hbxwp.JobTrackerPort.toString());

        c.set("username", hbxwp.Username);
        c.set("password", hbxwp.Password);
        c.set("hostname", hbxwp.Hostname);

        c.set("source", hbxwp.Source);
        c.set("target", hbxwp.Target);
        c.set("stats", hbxwp.Stats);

        c.set("resources_username",hbxwp.ResourcesUsername);
        c.set("resources_password",hbxwp.ResourcesPassword);
        c.set("resources_hostname",hbxwp.ResourcesHostname);
        c.set("resources_location",hbxwp.ResourcesLocation);
        c.set("handbrake", hbxwp.Handbrake_x64);
        c.set("mkvinfo", hbxwp.MKVInfo);
        c.set("mkvextract", hbxwp.MKVExtract);
        c.set("ssaconverter", hbxwp.SSAConverter);

//        c.unset("fs.default.name"); // tried to remove warning from hadoop about fs.default.name being deprecated, but job submission barfs.  Go hadoop.
        return;
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new HBXMapReduce2(), args);
        if( res != 0 ) {
            throw new Exception("ToolRunner failed on HBXMapReduce.");
        }
//        System.exit(res);
    }

    @Override
    public boolean Submit( Map<String,String> mapParameters ) {
        List<String> aArgs = new ArrayList<String>();

        for( Map.Entry<String,String> e : mapParameters.entrySet() ) {
            aArgs.add(String.format("/%s:%s",e.getKey(),e.getValue()));
        }

        String[] args = aArgs.toArray(new String[aArgs.size()]);

        try {
            int res = ToolRunner.run(new Configuration(), new HBXMapReduce2(), args);
            if( res != 0 ) {
                return false;
            }
        }
        catch( Exception ex ) {
            System.out.println("HBXMapReduce2.Submit - ToolRunner.run exceptioned.");
            System.out.println(ex.getMessage());
            return false;
        }
        return true;
    }

    public void ExecuteMain(String[] args) throws Exception {
        HBXMapReduce2.main(args);
    }
}
