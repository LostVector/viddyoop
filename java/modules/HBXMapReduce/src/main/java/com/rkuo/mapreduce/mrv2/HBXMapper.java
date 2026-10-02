package com.rkuo.mapreduce.mrv2;

import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.*;

import java.io.IOException;

public class HBXMapper extends Mapper<LongWritable,Text,LongWritable,Text> {

    // map will assign a key to the text value
    // since in this case our function does no real work, we will assign a key of 1 to any value we receive
    // this will cause only 1 reduce task to be run
    @Override
	protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
//        context.write(value,new IntWritable(1));
        context.write(new LongWritable(1), value);
        return;
	}
}