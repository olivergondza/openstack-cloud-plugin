package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Vijay Kiran
 */
public class JCloudsRetentionStrategy extends RetentionStrategy<JCloudsComputer> implements ExecutorListener {
    private transient ReentrantLock checkLock;

    @DataBoundConstructor
    public JCloudsRetentionStrategy() {
        readResolve();
    }

    @Override
    public long check(JCloudsComputer c) {
        if (disabled) {
            LOGGER.fine("Skipping check - disabled");
            return 1;
        }
        LOGGER.fine("Checking");

        if (!checkLock.tryLock()) {
            LOGGER.info("Failed to acquire retention lock - skipping");
            return 1;
        }

        try {
            doCheck(c);
        } finally {
            checkLock.unlock();
        }
        return 1;
    }

    private void doCheck(JCloudsComputer c) {
        if (c.isPendingDelete()) return; // No need to do it again
        if (c.isConnecting()) return; // Do not discard slave while launching for the first time when "idle time" does not make much sense

        final JCloudsSlave node = c.getNode();
        if (node == null) return; // Node is gone already

        final int retentionTime = node.getSlaveOptions().getRetentionTime();
        if (retentionTime < 0) return; // Keep forever

        if (retentionTime !=0 && !c.isIdle()) return;
        if (c.getOfflineCause() instanceof OfflineCause.UserCause) return; // Occupied by user initiated activity

        final long idleSince = c.getIdleStartMilliseconds();
        final long idleMilliseconds = System.currentTimeMillis() - idleSince;
        if (retentionTime == 0 || idleMilliseconds > TimeUnit2.MINUTES.toMillis(retentionTime)) {
            if (JCloudsPreCreationThread.shouldSlaveBeRetained(node)) {
                LOGGER.info("Keeping " + c .getName() + " to meet minium requirements");
                return;
            }
            LOGGER.info("Scheduling " + c .getName() + " for termination as it was idle since " + new Date(idleSince));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Jenkins.XSTREAM2.toXMLUTF8(node, out);
                LOGGER.fine(out.toString("UTF-8"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,"Failed to dump node config", e);
            }
            c.setPendingDelete(true);
        }
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(JCloudsComputer c) {
        c.connect(false);
    }

    // no @Extension since this retention strategy is used only for cloud nodes that we provision automatically.
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "JClouds";
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        checkSlaveAfterTaskCompletion(executor.getOwner());
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        checkSlaveAfterTaskCompletion(executor.getOwner());
    }

    private void checkSlaveAfterTaskCompletion(Computer computer) {
        // If the retention time for this computer is zero, this means it
        // should not be re-used: force a check to set this computer as
        // "pending delete".
        if (computer instanceof JCloudsComputer) {
            final JCloudsComputer cloudComputer = (JCloudsComputer) computer;
            if (cloudComputer == null) return;

            final JCloudsSlave node = cloudComputer.getNode();
            if (node == null) return;
            final int retentionTime = node.getSlaveOptions().getRetentionTime();
            if (retentionTime == 0) {
                doCheck(cloudComputer);
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());

    @SuppressFBWarnings({"MS_SHOULD_BE_FINAL", "Left modifiable from groovy"})
    /*package*/ static boolean disabled = Boolean.getBoolean(JCloudsRetentionStrategy.class.getName() + ".disabled");
}
