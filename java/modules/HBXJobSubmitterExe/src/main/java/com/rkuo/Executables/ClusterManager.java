package com.rkuo.Executables;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.rkuo.logging.RKLog;
import com.rkuo.net.ssh.Scp;
import com.rkuo.util.Misc;
import com.rkuo.util.OperatingSystem;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 2/4/14
 * Time: 11:51 PM
 */
public class ClusterManager {

    /*
        Given a ResourceManager and list of node managers, attempt to size the cluster to an appropriate size.
     */
    public static void SizeCluster(String rmHostname, Integer rmPort, ClusterSizingStrategy css, String wakeExe, Map<String, RkTracker> trackers) {

        int activeNodeCount, activatingNodeCount = 0;
        int jobCount = 0;

        Configuration c = new Configuration();
        c.set("yarn.resourcemanager.address", rmHostname + ":" + rmPort.toString());

        Long now = System.currentTimeMillis();

        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(c);
        yarnClient.start();

        try {
            Set<String> busyHostnames = new HashSet<String>();
            List<String> hostnames = new ArrayList<String>(); // all running nodes returned by the ResourceManager

            // count all non-terminal applications (queued + running) as the outstanding job queue
            EnumSet<YarnApplicationState> outstandingStates = EnumSet.of(
                    YarnApplicationState.NEW,
                    YarnApplicationState.NEW_SAVING,
                    YarnApplicationState.SUBMITTED,
                    YarnApplicationState.ACCEPTED,
                    YarnApplicationState.RUNNING);
            jobCount = yarnClient.getApplications(outstandingStates).size();

            // collect the running nodes and note which ones currently have containers allocated
            for (NodeReport nr : yarnClient.getNodeReports(NodeState.RUNNING)) {
                String hostname = nr.getNodeId().getHost();

                if( nr.getNumContainers() > 0 ) {
                    busyHostnames.add(hostname);
                }

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

            // get some node counts in preparation for the next loop
            activeNodeCount = hostnames.size();               // count all active nodes
            for( RkTracker t : trackers.values() ) {          // and count all activating nodes
                if( t.State == RkTracker.STATE_ACTIVATING ) {
                    activatingNodeCount++;
                }
            }

            if( css == ClusterSizingStrategy.EFFICIENT ) {
                SizeEfficient(now, wakeExe, jobCount, activeNodeCount, activatingNodeCount, trackers, busyHostnames);
            }
            else if( css == ClusterSizingStrategy.MANUALON ) {
                SizeManualOn(now, jobCount, activeNodeCount, trackers, busyHostnames);
            }
            else {
                SizeAggressive(now, wakeExe, jobCount, activeNodeCount, activatingNodeCount, trackers, busyHostnames);
            }
        }
        catch( Exception ex ) {
            return;
        }
        finally {
            yarnClient.stop();
        }

        return;
    }

    // this will only spin up nodes once the job queue exceeds a certain number
    // this allows us to give the energy efficient nodes most of the work and only spin up new nodes
    // when we have a big backlog
    protected static void SizeEfficient(Long now, String wakeExe, int jobCount, int activeNodeCount, int activatingNodeCount, Map<String, RkTracker> trackers, Set<String> busyHostnames) {
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
                if( busyHostnames.contains(t.Hostname) == true ) {
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
    protected static void SizeAggressive(Long now, String wakeExe, int jobCount, int activeNodeCount, int activatingNodeCount, Map<String, RkTracker> trackers, Set<String> busyHostnames) {
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
                if( busyHostnames.contains(t.Hostname) == true ) {
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
    protected static void SizeManualOn(Long now, int jobCount, int activeNodeCount, Map<String, RkTracker> trackers, Set<String> busyHostnames) {
        int newDeactivatingCount = 0;

        // now check if we need to deactivate nodes
        if( jobCount < activeNodeCount ) {
            for( Map.Entry<String, RkTracker> e : trackers.entrySet() ) {
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
                if( busyHostnames.contains(t.Hostname) == true ) {
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

    // This will retrieve a list of NodeManagers to manage.
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
}
