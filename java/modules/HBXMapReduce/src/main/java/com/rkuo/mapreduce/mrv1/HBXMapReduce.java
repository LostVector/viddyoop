package com.rkuo.mapreduce.mrv1;

import com.rkuo.Executables.HBXHadoopWrapperLogic;
import com.rkuo.handbrake.HBXWrapperParams;
import com.rkuo.handbrake.IHBXExecutor;
import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.util.CommandLineParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class HBXMapReduce extends Configured implements Tool {

    public static class Map extends MapReduceBase implements Mapper<LongWritable, Text, LongWritable, Text> {

//        static enum Counters {INPUT_WORDS}

//        private final static IntWritable one = new IntWritable(1);
//        private Text word = new Text();
//
//        private boolean caseSensitive = true;
//        private Set<String> patternsToSkip = new HashSet<String>();
//
//        private long numRecords = 0;
        private String inputFile;

        public void configure(JobConf job) {
            inputFile = job.get("map.input.file");

/*            caseSensitive = job.getBoolean("wordcount.case.sensitive", true);
            if (job.getBoolean("wordcount.skip.patterns", false)) {
                Path[] patternsFiles = new Path[0];
                try {
                    patternsFiles = DistributedCache.getLocalCacheFiles(job);
                } catch (IOException ioe) {
                    System.err.println("Caught exception while getting cached files: " + StringUtils.stringifyException(ioe));
                }
                for (Path patternsFile : patternsFiles) {
                    parseSkipFile(patternsFile);
                }
            }
 */
            return;
        }
/*
        private void parseSkipFile(Path patternsFile) {
            try {
                BufferedReader fis = new BufferedReader(new FileReader(patternsFile.toString()));
                String pattern = null;
                while ((pattern = fis.readLine()) != null) {
                    patternsToSkip.add(pattern);
                }
            } catch (IOException ioe) {
                System.err.println("Caught exception while parsing the cached file '" + patternsFile + "' : " + StringUtils.stringifyException(ioe));
            }
        }
 */
        public void map(LongWritable key, Text value, OutputCollector<LongWritable, Text> output, Reporter reporter) throws IOException {
/*            String line = (caseSensitive) ? value.toString() : value.toString().toLowerCase();

            for (String pattern : patternsToSkip) {
                line = line.replaceAll(pattern, "");
            }

            StringTokenizer tokenizer = new StringTokenizer(line);
            while (tokenizer.hasMoreTokens()) {
                word.set(tokenizer.nextToken());
                output.collect(word, one);
                reporter.incrCounter(Counters.INPUT_WORDS, 1);
            }

            if ((++numRecords % 100) == 0) {
                reporter.setStatus("Finished processing " + numRecords + " records " + "from the input file: " + inputFile);
            }
            */
//            word.set(value.toString());
//            output.collect( key, value );
            output.collect( new LongWritable(1), value );
        }
    }

    public static class Reduce extends MapReduceBase implements Reducer<LongWritable, Text, LongWritable, Text> {

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

        protected String Resources;
        protected String HandBrake;
        protected String MKVInfo;
        protected String MKVExtract;
        protected String SSAConverter;

        public void configure( JobConf job ) {
            Username = job.get("username");
            Password = job.get("password");
            Hostname = job.get("hostname");

            ResourcesUsername = job.get("resources_username");
            ResourcesPassword = job.get("resources_password");
            ResourcesHostname = job.get("resources_hostname");
            ResourcesLocation = job.get("resources_location");

            Source = job.get("source");
            Target = job.get("target");
            Stats = job.get("stats");

            Resources = job.get("resources");
            HandBrake = job.get("handbrake");
            MKVInfo = job.get("mkvinfo");
            MKVExtract = job.get("mkvextract");
            SSAConverter = job.get("ssaconverter");

//            System.out.format("HBXMapReduce::configure - HandBrake = %s\n", HandBrake);
            return;
        }

        public void reduce(LongWritable key, Iterator<Text> values, OutputCollector<LongWritable, Text> output, Reporter reporter) throws IOException {

            boolean br;

            File d = new File(".");

            System.out.format("HBXMapReduce::reduce started\n");

            while( values.hasNext() == true ) {
                Text val = values.next();
                output.collect(key, val);
            }
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

            hbxwp.ResourcesUsername = ResourcesUsername;
            hbxwp.ResourcesPassword = ResourcesPassword;
            hbxwp.ResourcesHostname = ResourcesHostname;
            hbxwp.ResourcesLocation = ResourcesLocation;

            hbxwp.Source = Source;
            hbxwp.Target = Target;
            hbxwp.Stats = Stats;

            hbxwp.Handbrake_x64 = HandBrake;
            hbxwp.MKVInfo = MKVInfo;
            hbxwp.MKVExtract = MKVExtract;
            hbxwp.SSAConverter = SSAConverter;

            hbxwp.TempDirectory = d.getCanonicalPath();
//            hbxwp.TempDirectory = "";

            IHBXExecutor executor;
            IHandBrakeExeCallback callback;

            callback = new HBXHadoopHandBrakeExeCallback(reporter);

            executor = new HBXHadoopWrapperLogic(callback);

            br = executor.Execute( hbxwp );
            if( br == false ) {
                throw new IOException("HBXHadoopWrapperLogic::Execute failed.");
            }

//          HBXExeHelper.ExecuteHandbrake095ForAppleTV2012();

            System.out.format("HBXMapReduce::reduce finished\n");
            return;
        }
    }

    public int run(String[] args) throws Exception {
        JobConf jc;
        HBXWrapperParams hbxwp;
        String inputPath, outputPath;
        File f;

        System.out.format("HBXMapReduce::run begins.\n" );

        Configuration c = getConf();
        CommandLineParser clp = new CommandLineParser();

        clp.Parse( args );

        hbxwp = HBXWrapperParams.GetHBXWrapperParams( clp );
        if( hbxwp == null ) {
            return 1;
        }

        // input
        if( clp.Contains("input") == false ) {
            return 1;
        }

        inputPath = clp.GetString("input");

        // output
        if( clp.Contains("output") == false ) {
            return 1;
        }

        outputPath = clp.GetString("output");

        jc = new JobConf(c, HBXMapReduce.class);

//        jc.set("fs.default.name", "hdfs://jobtracker.domain.com:8020");
        jc.set("fs.defaultFS", "hdfs://jobtracker.domain.com:8020");
        jc.set("mapred.job.tracker", "jobtracker.domain.com:8021");

        f = new File( hbxwp.Source );

        jc.setJobName( f.getName() );

        // this setJarByClass call is critical.  I don't know why the new JobConf call above doesn't handle this.
        jc.setJarByClass(Map.class);
        System.out.format("getJar() - %s\n", jc.getJar());

        jc.setOutputKeyClass(LongWritable.class);
        jc.setOutputValueClass(Text.class);

        jc.setMapperClass(Map.class);
//        jc.setCombinerClass(Reduce.class);
        jc.setReducerClass(Reduce.class);

        jc.setInputFormat(TextInputFormat.class);
        jc.setOutputFormat(TextOutputFormat.class);
/*
        List<String> other_args = new ArrayList<String>();
        for (int i = 0; i < args.length; ++i) {
            if ("-skip".equals(args[i])) {
                DistributedCache.addCacheFile(new Path(args[++i]).toUri(), jc);
                jc.setBoolean("wordcount.skip.patterns", true);
            } else {
                other_args.add(args[i]);
            }
        }
 */

        jc.set("username",hbxwp.Username);
        jc.set("password",hbxwp.Password);
        jc.set("hostname",hbxwp.Hostname);

        jc.set("source",hbxwp.Source);
        jc.set("target",hbxwp.Target);
        jc.set("stats",hbxwp.Stats);

        jc.set("resources_username",hbxwp.ResourcesUsername);
        jc.set("resources_password",hbxwp.ResourcesPassword);
        jc.set("resources_hostname",hbxwp.ResourcesHostname);
        jc.set("resources_location",hbxwp.ResourcesLocation);
        jc.set("handbrake",hbxwp.Handbrake_x64);
        jc.set("mkvinfo",hbxwp.MKVInfo);
        jc.set("mkvextract",hbxwp.MKVExtract);
        jc.set("ssaconverter",hbxwp.SSAConverter);

//        jc.setStrings( "handbrake_x86", hbxwp.Handbrake_x86 );
//        jc.setStrings( "handbrake_x64", hbxwp.Handbrake_x64);

//        System.out.format("setInputPaths: %s\n", inputPath );
        FileInputFormat.setInputPaths( jc, new Path(inputPath) );

//        System.out.format("setOutputPath: %s\n", outputPath );
        FileOutputFormat.setOutputPath( jc, new Path(outputPath) );

        // runJob is blocking, submitJob is non-blocking
//        System.out.format("Attempting to submit job for %s.\n", f.getName() );
        JobClient jcl = new JobClient(jc);
        RunningJob rj = jcl.submitJob(jc);
        System.out.format("Job submitted for %s.\n", f.getName() );
        return 0;
    }

    // http://lordjoesoftware.blogspot.com/2010/08/accessing-local-files.html
/*
    public static void PrintFiles(String path) {

        String files;

        int count = 0;
        File folder = new File(path);
        File[] listOfFiles = folder.listFiles();

        for (int i = 0; i < listOfFiles.length; i++) {

            if ( listOfFiles[i].isFile() == true ) {
                files = listOfFiles[i].getName();
                count++;

                System.out.println(files);
            }
        }

        System.out.format("Printed %d files.\n", count);
    }
    */

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new HBXMapReduce(), args);
        if( res != 0 ) {
            throw new Exception("ToolRunner failed on HBXMapReduce.");
        }
//        System.exit(res);
    }
}
