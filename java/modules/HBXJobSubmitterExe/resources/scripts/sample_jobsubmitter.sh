#!/bin/bash
echo -n -e "\033]0;Submitting jobs to Hadoop\007"
export HADOOP_USER_NAME=vidoop
java -cp HBXJobSubmitter.jar:HBXMapReduce.jar:$(hadoop classpath) com.rkuo.Executables.HBXJobSubmitterExe\
 /hadoop_hdfs:"hdfs://cloudera-master.domain.com:8020"\
 /hadoop_jobtracker:"cloudera-master.domain.com"\
 /hadoop_jobtracker_port:"8030"\
 /hadoop_tasktrackers:"/home/root/cloudera/scripts/trackers.xml"\
 /preprocessor_jar:"/home/root/cloudera/scripts/HBXJobPreprocessor.jar"\
 /preprocessor_class:"com.rkuo.hadoop.HBXJobPreprocessor"\
 /preprocessor_configuration:"/home/root/cloudera/scripts/preprocessor.xml"\
 /mapreduce_jar:"/home/root/cloudera/scripts/HBXMapReduce.jar"\
 /mapreduce_class:"com.rkuo.mapreduce.mrv2.HBXMapReduce2"\
 /mapreduce_configuration:"/home/root/cloudera/scripts/mapreduce.xml"\
 /hadoop_input:"/home/root/cloudera/hadoop/input"\
 /hadoop_output:"/home/root/cloudera/hadoop/output"\
 /hadoop_logs:"/home/root/cloudera/hadoop/logs/new"\
 /local_wakeonlan:"/usr/sbin/etherwake"

