package com.rkuo.mapreduce.mrx264;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/20/14
 * Time: 4:58 PM
 * To change this template use File | Settings | File Templates.
 */
public class X264OutputFormat<K,V> extends TextOutputFormat<K,V> {

    /** Output committer */
    protected FileOutputCommitter committer;

    /**
     * Implementation copied from the parent but amended to override the
     * {@link OutputCommitter#needsTaskCommit(TaskAttemptContext)} method
     */
    @Override
    public synchronized OutputCommitter getOutputCommitter( TaskAttemptContext context ) throws IOException {
        // annoyingly committer is private in the parent class
        if( committer == null ) {
            Path output = getOutputPath(context);
            committer = new FileOutputCommitter(output, context) {
                @Override
                public boolean needsTaskCommit(TaskAttemptContext context) throws IOException {
                    return super.needsTaskCommit(context);
                }

                @Override
                public void abortJob(JobContext context, JobStatus.State state) throws IOException {
                    super.abortJob(context, state);    //To change body of overridden methods use File | Settings | File Templates.
                    System.out.format("X264OutputFormat.abortJob\n");
                    Path output = getOutputPath(context);
                    System.out.format("output path = %s\n",output.toString());
                    System.out.format("user = %s\n",context.getUser());

//                    Configuration conf = new Configuration();
//                    conf.set("fs.defaultFS", hdfsUrl);
//                    conf.set("hadoop.job.ugi", "hdfs");

                    FileSystem fs = FileSystem.get(context.getConfiguration());

                    // will not error if path exists
//                    fs.delete(output,true); // recursive delete
                }

                @Override
                public void commitJob(JobContext context) throws IOException {
                    super.commitJob(context);    //To change body of overridden methods use File | Settings | File Templates.
                    System.out.format("X264OutputFormat.commitJob\n");
                    Path output = getOutputPath(context);
                    System.out.format("output path = %s\n",output.toString());
                    System.out.format("user = %s\n",context.getUser());

                    // join files and remux
                    //
                }
            };
        }
        return committer;
    }
}
