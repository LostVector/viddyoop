package com.rkuo.handbrake;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 6:16:45 PM
 * To change this template use File | Settings | File Templates.
 */
public class Importer {
    private Thread thread = null;

    public Importer() {
        thread = new Thread("Sample thread") {

            public void run() {
                while (true) {
                    System.out.println("[Sample thread] Sample thread speaking...");
                    try {
                        Thread.currentThread().sleep(1000);
                    } catch( InterruptedException ie ) {
                        break;
                    }
                }
                System.out.println("[Sample thread] Stopped");
            }
            
        };
        thread.start();
    }

    public void stopThread() {
        thread.interrupt();
    }

    public void WorkerThread() {

    }
}
