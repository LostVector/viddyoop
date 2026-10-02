package com.rkuo.Executables;

import com.rkuo.handbrake.HBXWrapperParams;
import junit.framework.Assert;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

@RunWith(JUnit4.class)
public class TestHadoopJobSubmitter extends TestCase {
    
    @Test
    public void testSubmit() {
//        boolean br;
//
//        HBXWrapperParams wp = new HBXWrapperParams();
//        IJobSubmitter js = new HadoopJobSubmitter();
//
//        wp.Source = "/Volumes/Storage/Shared/HBX/temp/test.mkv";
//        wp.Target = "/Volumes/Storage/Shared/HBX/temp/test.m4v";
//        wp.Stats = "/Volumes/Storage/Shared/HBX/temp/test.mkv.encodedstats.xml";
//        wp.Username = "";
//        wp.Password = "";
//        wp.Hostname = "";
//        wp.Handbrake_x64 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI-x64-0.9.5-ubuntu";
//
//        br = js.Submit(wp);
//        Assert.assertTrue(br);
        return;
    }

    @Test
    public void testJobClient() {
//        String wakeExe = "/Users/root/Downloads/wolcmd";
//        Map<String,RkTracker> trackers = ClusterManager.GetTrackers();
//        ClusterManager.SizeCluster("jobtracker.domain.com",8021,ClusterSizingStrategy.AGGRESSIVE,wakeExe,trackers);
        return;
    }

    @Test
    public void testWakeOnLanOSX() {
        ClusterManager.WakeOnLan("/Users/root/Downloads/wolcmd","002522779ada");
        return;
    }

    @Test
    public void testRemoteShutdown() {
        ClusterManager.RemoteShutdown("","","");
        return;
    }

    @Test
    public void testIsTaskTrackerRunning() {
        ClusterManager.IsTaskTrackerRunning("jobtracker.domain.com");
        return;
    }

    @Test
    public void testGetTrackers() {
        ClusterManager.GetTrackers("/Users/root/Downloads/hbxtest/trackers.xml");
        return;
    }
}
