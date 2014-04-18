package com.rkuo.mapreduce.mrx264;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/17/14
 * Time: 8:34 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXMapper264 extends Mapper<LongWritable,Text,LongWritable,Text> {

    protected String Username;
    protected String Password;
    protected String Hostname;

    protected String Source;

    @Override
    protected void setup(Mapper.Context context) throws IOException, InterruptedException {
        Configuration c = context.getConfiguration();

        Username = c.get("username");
        Password = c.get("password");
        Hostname = c.get("hostname");

        Source = c.get("source");
        return;
    }

    // map will assign a key to the text value (for text input format, this is a line number)
    // since in this case our function does no real work, we will assign a key of 1 to any value we receive
    // this will cause only 1 reduce task to be run
    @Override
	protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

        // get frame count

        // chunk the input file according to a fixed duration (60s)
        // this can be improved eventually so that we use a stats file to intelligently decide where
        // the best placement for a split will be

        // distribute
//        context.write(value,new IntWritable(1));
        context.write(new LongWritable(1), value);
        return;
	}
}
