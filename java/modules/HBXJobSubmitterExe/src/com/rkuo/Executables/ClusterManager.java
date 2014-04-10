package com.rkuo.Executables;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.rkuo.logging.RKLog;
import com.rkuo.net.ssh.Scp;
import com.rkuo.util.Misc;
import com.rkuo.util.OperatingSystem;
import com.rkuo.web.WebServices;
import com.rkuo.xml.XMLHelper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.RunningJob;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 2/4/14
 * Time: 11:51 PM
 * To change this template use File | Settings | File Templates.
 */
public class ClusterManager {

    /*
        Given a job tracker and list of task trackers, attempt to size the cluster to an appropriate size.
     */
    public static void SizeCluster(String jobHostname, Integer jobPort, ClusterSizingStrategy css, String wakeExe, Map<String, RkTracker> trackers) {

        JobClient jcl;
        JobStatus[] jobs;
        ClusterStatus cs;
        int activeNodeCount, activatingNodeCount = 0;

        Configuration c = new Configuration();
        Long now = System.currentTimeMillis();

        try {
            Collection<String> activeTrackers;
            List<String> hostnames = new ArrayList<String>(); // all tasktrackers returned by the jobtracker

            jcl = new JobClient(new InetSocketAddress(jobHostname, jobPort), c);

//            jt = new JobTracker();
//            Collection<TaskTrackerStatus> activeTrackers;
//
//            activeTrackers = jt.activeTaskTrackers();

            jcl.setConf(c); // CDH has a bug where the conf in the constructor is not properly stored!
            jobs = jcl.jobsToComplete();
            cs = jcl.getClusterStatus(true);
            activeTrackers = cs.getActiveTrackerNames();

            // parse the hostnames out of the hadoop tracker list
            // and update our own list of trackers with the ones active in Hadoop
            for( String activeTracker : activeTrackers ) {
                int first = activeTracker.indexOf("_");
                int last = activeTracker.indexOf(":");
                String hostname = activeTracker.substring(first + 1, last);

                if( trackers.containsKey(hostname) == true ) {
                    RkTracker t = trackers.get(hostname);
                    if( t.State != RkTracker.STATE_DEACTIVATING ) {
                        t.State = RkTracker.STATE_ACTIVE;
                    }
                }

                hostnames.add(hostname);
            }

            // any trackers we didn't find, mark as inactive
            // we also mark as inactive any trackers that have been in the activating state for too long
            for( RkTracker t : trackers.values() ) {
                boolean found = false;

                if( t.State == RkTracker.STATE_INACTIVE ) {
                    continue;
                }

                if( t.State == RkTracker.STATE_ACTIVE ) {
                    continue;
                }

                if( t.State == RkTracker.STATE_ACTIVATING ) {
                    if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                        continue;
                    }
                }

                for( String hostname : hostnames ) {
                    if( t.Hostname.compareToIgnoreCase(hostname) == 0 ) {
                        found = true;
                        break;
                    }
                }

                if( found == true ) {
                    continue;
                }

                t.State = RkTracker.STATE_INACTIVE;
            }

            // just for debugging
            for( JobStatus js : jobs ) {
                RunningJob rj = jcl.getJob(js.getJobID());
//                System.out.format("Name: %s\n", rj.getJobName());
//                System.out.format("Tracking URL: %s\n", rj.getTrackingURL());
//                System.out.format("JobID: %s\n", js.getJobID().toString());
//                System.out.format("Scheduling info: %s\n", js.getSchedulingInfo());
            }

            // get some node counts in preparation for the next loop
            activeNodeCount = hostnames.size();               // count all active nodes
            for( RkTracker t : trackers.values() ) {          // and count all activating nodes
                if( t.State == RkTracker.STATE_ACTIVATING ) {
                    activatingNodeCount++;
                }
            }

            if( css == ClusterSizingStrategy.EFFICIENT ) {
                SizeEfficient(now, wakeExe, jobs.length, activeNodeCount, activatingNodeCount, trackers);
            }
            else if( css == ClusterSizingStrategy.MANUALON ) {
                SizeManualOn(now, jobs.length, activeNodeCount, trackers);
            }
            else {
                SizeAggressive(now, wakeExe, jobs.length, activeNodeCount, activatingNodeCount, trackers);
            }
        }
        catch( Exception ex ) {
            return;
        }

        return;
    }

    // this will only spin up nodes once the job queue exceeds a certain number
    // this allows us to give the energy efficient nodes most of the work and only spin up new nodes
    // when we have a big backlog
    protected static void SizeEfficient(Long now, String wakeExe, int jobCount, int activeNodeCount, int activatingNodeCount, Map<String, RkTracker> trackers) {
        int newActivatingCount = 0;
        int newDeactivatingCount = 0;
        int MAX_QUEUE_SPINUP_DELTA = 10; // if the queue grows to 10 more than the number of active nodes, then start spinning up
        int MAX_QUEUE_SPINDOWN_DELTA = 5; // if the queue shrinks to 5 more than the number of active nodes, then start spinning down

        // if we have more jobs than nodes activating + active, we want to activate more nodes
        if( jobCount > activatingNodeCount + activeNodeCount + MAX_QUEUE_SPINUP_DELTA ) {

            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
                RkTracker t = e.getValue();

                if( t.Managed == false ) {
                    continue;
                }

                if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                    continue;
                }

                if( t.State != RkTracker.STATE_INACTIVE ) {
                    continue;
                }

                t.State = RkTracker.STATE_ACTIVATING;
                t.LastAction = now;

                RKLog.Log("%d job(s) found. %d node(s) activating (%d) or active (%d).",
                        jobCount,
                        activatingNodeCount + activeNodeCount,
                        activatingNodeCount,
                        activeNodeCount);
                RKLog.Log("Waking %s.", t.Hostname);
                WakeOnLan(wakeExe, t.MacAddress);

                newActivatingCount++;

                // stop activating more nodes when we have activated enough to handle all outstanding work
                if( jobCount <= newActivatingCount + activatingNodeCount + activeNodeCount + MAX_QUEUE_SPINUP_DELTA ) {
                    break;
                }
            }
        }

        // now check if we need to deactivate nodes
        if( jobCount < activeNodeCount + MAX_QUEUE_SPINDOWN_DELTA ) {
            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
                boolean br;

                RkTracker t = e.getValue();

                if( t.Managed == false ) {
                    continue;
                }

                if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                    continue;
                }

                if( t.State != RkTracker.STATE_ACTIVE ) {
                    continue;
                }

                // we want to shut down an active node that is doing no work ... not just any node
                br = IsTaskTrackerRunning(t.Hostname);
                if( br == true ) {
                    continue;
                }

                t.State = RkTracker.STATE_DEACTIVATING;
                t.LastAction = now;

                RKLog.Log("%d job(s) found. %d node(s) active.", jobCount, activeNodeCount);
                RKLog.Log("Shutting down %s.", t.Hostname);
                RemoteShutdown(t.Username, t.Password, t.Hostname);

                newDeactivatingCount++;

                if( jobCount >= activeNodeCount + MAX_QUEUE_SPINDOWN_DELTA - newDeactivatingCount ) {
                    break;
                }
            }
        }
        return;
    }

    // this simply spins up new nodes if we have more jobs than nodes
    // it also spins down nodes if we have less jobs than nodes
    protected static void SizeAggressive(Long now, String wakeExe, int jobCount, int activeNodeCount, int activatingNodeCount, Map<String, RkTracker> trackers) {
        int newActivatingCount = 0;
        int newDeactivatingCount = 0;

        // if we have more jobs than nodes activating + active, we want to activate more nodes
        if( jobCount > activatingNodeCount + activeNodeCount ) {

            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
                RkTracker t = e.getValue();

                if( t.Managed == false ) {
                    continue;
                }

                if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                    continue;
                }

                if( t.State != RkTracker.STATE_INACTIVE ) {
                    continue;
                }

                t.State = RkTracker.STATE_ACTIVATING;
                t.LastAction = now;

                RKLog.Log("%d job(s) found. %d node(s) activating (%d) or active (%d).",
                        jobCount,
                        activatingNodeCount + activeNodeCount,
                        activatingNodeCount,
                        activeNodeCount);
                RKLog.Log("Waking %s.", t.Hostname);
                WakeOnLan(wakeExe, t.MacAddress);

                newActivatingCount++;

                // stop activating more nodes when we have activated enough to handle all outstanding work
                if( jobCount <= newActivatingCount + activatingNodeCount + activeNodeCount ) {
                    break;
                }
            }
        }

        // now check if we need to deactivate nodes
        if( jobCount < activeNodeCount ) {
            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
                boolean br;

                RkTracker t = e.getValue();

                if( t.Managed == false ) {
                    continue;
                }

                if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                    continue;
                }

                if( t.State != RkTracker.STATE_ACTIVE ) {
                    continue;
                }

                // we want to shut down an active node that is doing no work ... not just any node
                br = IsTaskTrackerRunning(t.Hostname);
                if( br == true ) {
                    continue;
                }

                t.State = RkTracker.STATE_DEACTIVATING;
                t.LastAction = now;

                RKLog.Log("%d job(s) found. %d node(s) active.", jobCount, activeNodeCount);
                RKLog.Log("Shutting down %s.", t.Hostname);
                RemoteShutdown(t.Username, t.Password, t.Hostname);

                newDeactivatingCount++;

                if( jobCount >= activeNodeCount - newDeactivatingCount ) {
                    break;
                }

                // TODO: we will want to be more aggressive about shutting down nodes at some point
            }
        }

        return;
    }

    // does not spin up machines. only turns them off when appropriate
    protected static void SizeManualOn(Long now, int jobCount, int activeNodeCount, Map<String, RkTracker> trackers) {
        int newDeactivatingCount = 0;

        // now check if we need to deactivate nodes
        if( jobCount < activeNodeCount ) {
            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
                boolean br;

                RkTracker t = e.getValue();

                if( t.Managed == false ) {
                    continue;
                }

                if( now < t.LastAction + RkTracker.NODE_ACTIVATION_TIMEOUT ) {
                    continue;
                }

                if( t.State != RkTracker.STATE_ACTIVE ) {
                    continue;
                }

                // we want to shut down an active node that is doing no work ... not just any node
                br = IsTaskTrackerRunning(t.Hostname);
                if( br == true ) {
                    continue;
                }

                t.State = RkTracker.STATE_DEACTIVATING;
                t.LastAction = now;

                RKLog.Log("%d job(s) found. %d node(s) active.", jobCount, activeNodeCount);
                RKLog.Log("Shutting down %s.", t.Hostname);
                RemoteShutdown(t.Username, t.Password, t.Hostname);

                newDeactivatingCount++;

                if( jobCount >= activeNodeCount - newDeactivatingCount ) {
                    break;
                }
            }
        }

        return;
    }

    protected static List<String> GetTrackers(JobClient jcl) {
        return null;
    }

    protected static void WakeOnLan(String exe, String address) {
        if( OperatingSystem.isMac() == true ) {
            WakeOnLanOSX(exe, address);
            return;
        }

        if( OperatingSystem.isUnix() == true ) {
            WakeOnLanUnix(exe, address);
            return;
        }

        return;
    }

    protected static void WakeOnLanUnix(String exe, String address) {
        // wake up the big computer
        int nr;

        List<String> args = new ArrayList<String>();
        args.add(exe);
        args.add(address);
        nr = Misc.ExecuteProcess(args.toArray(new String[args.size()]));
        if( nr != 0 ) {
            return;
        }

        return;
    }

    protected static void WakeOnLanOSX(String exe, String address) {
        // wake up the big computer
        int nr;

        List<String> args = new ArrayList<String>();
        args.add(exe);
        args.add(address);
        args.add("255.255.255.255");
        args.add("255.255.255.255");
        args.add("9");
        nr = Misc.ExecuteProcess(args.toArray(new String[args.size()]));
        if( nr != 0 ) {
            return;
        }

        return;
    }

    protected static boolean RemoteShutdown(String username, String password, String hostname) {

        try {
            Scp.SSHExec(username, password, hostname, "shutdown -h now");
        }
        catch( Exception ex ) {
            RKLog.Log("Remote shutdown exceptioned.");
            RKLog.println(ex.getMessage());
            return false;
        }

        return true;
    }

    // This will retrieve a list of TaskTrackers to manage.
    protected static Map<String, RkTracker> GetTrackers(String filename) {

        XmlMapper xmlMapper = new XmlMapper();
        Map<String,RkTracker> mapTrackers = new HashMap<String, RkTracker>();

        try {
            RkTrackers o = xmlMapper.readValue(new File(filename), RkTrackers.class);
            for( RkTracker t : o.trackers ) {
                mapTrackers.put(t.Hostname,t);
            }
        }
        catch( IOException ioex ) {
            return mapTrackers;
        }

        return mapTrackers;
    }

    // This checks to see if the task trackers is running a task (not to see if it is alive).
    // would be nice if there was an api for this instead of having to scrape a web page!
    protected static boolean IsTaskTrackerRunning(String hostname) {
        Map<String, String> mapHeaders = new HashMap<String, String>();

        String html = WebServices.Get(String.format("http://%s:50060/tasktracker.jsp", hostname), mapHeaders);
        if( html == null ) {
            return false;
        }

        String tidy = XMLHelper.CleanXml(html);
        if( tidy == null ) {
            return false;
        }

        // check the response for captcha
        org.jsoup.nodes.Document doc = Jsoup.parse(tidy);
        Elements elsRunningTasks = doc.getElementsContainingOwnText("Running tasks");
        Element eCenter = elsRunningTasks.first().nextElementSibling();
        Elements elsRunning = eCenter.getElementsContainingOwnText("RUNNING");
        if( elsRunning.size() == 0 ) {
            return false;
        }

        return true;
    }
}
