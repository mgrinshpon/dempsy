package net.dempsy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dempsy.cluster.ClusterInfoException;
import net.dempsy.cluster.ClusterInfoSession;
import net.dempsy.cluster.DirMode;
import net.dempsy.intern.ApplicationState;
import net.dempsy.messages.Dispatcher;
import net.dempsy.messages.KeyedMessageWithType;
import net.dempsy.monitoring.NodeStatsCollector;
import net.dempsy.router.RoutingStrategy;
import net.dempsy.router.RoutingStrategy.ContainerAddress;
import net.dempsy.router.RoutingStrategyManager;
import net.dempsy.transport.NodeAddress;
import net.dempsy.transport.RoutedMessage;
import net.dempsy.transport.Sender;
import net.dempsy.transport.TransportManager;
import net.dempsy.util.SafeString;
import net.dempsy.utils.PersistentTask;

public class OutgoingDispatcher extends Dispatcher implements Service {
    public static Logger LOGGER = LoggerFactory.getLogger(OutgoingDispatcher.class);
    private static final long RETRY_TIMEOUT = 500L;

    private PersistentTask checkup;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final RoutingStrategyManager manager;
    private final AtomicReference<ApplicationState> outbounds = new AtomicReference<>(null);

    private final NodeAddress thisNode;
    private final String thisNodeId;
    private final TransportManager tmanager;
    private final NodeReceiver nodeReciever;
    private final AtomicBoolean isReady = new AtomicBoolean(false);
    private final NodeStatsCollector statsCollector;

    public OutgoingDispatcher(final RoutingStrategyManager manager, final NodeAddress thisNode, final String thisNodeId, final NodeReceiver nodeReciever,
            final TransportManager tmanager, final NodeStatsCollector statsCollector) {
        this.manager = manager;
        this.thisNode = thisNode;
        this.thisNodeId = thisNodeId;
        this.tmanager = tmanager;
        this.nodeReciever = nodeReciever;
        this.statsCollector = statsCollector;
    }

    @Override
    public void dispatch(final KeyedMessageWithType message) {
        boolean messageSentSomewhere = false;
        try {
            ApplicationState tmp = outbounds.get();

            // if we're in the midst of an update then we really want to wait for the new state.
            while (tmp == null) {
                if (!isRunning.get()) {
                    LOGGER.debug("Router dispatch called while stopped.");
                    return;
                }

                if (!isReady.get()) // however, if we never were ready then we're not in the midst
                                    // of an update.
                    throw new IllegalStateException("Dispatch used before Router is ready.");

                Thread.yield(); // let the other threads do their thing. Maybe we'll be updated sooner.
                tmp = outbounds.get(); // are we updated yet?
            }
            final ApplicationState cur = tmp;

            final Map<String, RoutingStrategy.Router[]> outboundsByMessageType = cur.outboundsByMessageType;

            // =================================================================================
            // For each message type, determine the set of Routers. The goal of this loop is to set
            // 'containerByNodeAddress'
            final Map<NodeAddress, ContainerAddress> containerByNodeAddress = new HashMap<>();
            for (final String mt : message.messageTypes) {
                final RoutingStrategy.Router[] routers = outboundsByMessageType.get(mt);
                if (routers == null)
                    LOGGER.trace("No cluster that handles messages of type {}", mt);
                else {
                    // For this message type we now have all of the Routers. For each Router determine
                    // the set of ContainerAddresses that this message will be sent to.
                    for (int i = 0; i < routers.length; i++) {
                        final ContainerAddress ca = routers[i].selectDestinationForMessage(message);
                        // it's possible 'ca' is null when we don't know where to send the message.
                        if (ca == null)
                            LOGGER.debug("No way to send the message {} to specific cluster for the time being", message.message);
                        else {
                            // When the message will be sent to 2 different clusters, but both clusters
                            // are hosted in the same node, then we send 1 message to 1 ContainerAddress
                            // where the 'clusters' field contains both container ids.
                            final ContainerAddress already = containerByNodeAddress.get(ca.node);
                            if (already != null) {
                                final int[] ia = new int[already.clusters.length + ca.clusters.length];
                                System.arraycopy(already.clusters, 0, ia, 0, already.clusters.length);
                                System.arraycopy(ca.clusters, 0, ia, already.clusters.length, ca.clusters.length);
                                containerByNodeAddress.put(ca.node, new ContainerAddress(ca.node, ia));
                            } else
                                containerByNodeAddress.put(ca.node, ca);
                        }
                    }
                }
            }
            // =================================================================================

            for (final Map.Entry<NodeAddress, ContainerAddress> e : containerByNodeAddress.entrySet()) {
                final NodeAddress curNode = e.getKey();
                final ContainerAddress curAddr = e.getValue();

                final RoutedMessage toSend = new RoutedMessage(curAddr.clusters, message.key, message.message);
                if (curNode.equals(thisNode)) {
                    nodeReciever.feedbackLoop(toSend, false); // this shouldn't count since Router is an OUTGOING class
                    messageSentSomewhere = true;
                } else {
                    final Sender sender = cur.getSender(curNode);
                    if (sender == null) {
                        // router update is probably behind the routing strategy update
                        if (isRunning.get())
                            LOGGER.error("Couldn't send message to " + curNode + " from " + thisNodeId + " because there's no "
                                    + Sender.class.getSimpleName());
                    } else {
                        sender.send(toSend);
                        messageSentSomewhere = true;
                    }
                }
            }

            if (containerByNodeAddress.size() == 0) {
                if (LOGGER.isTraceEnabled())
                    LOGGER.trace("There appears to be no valid destination addresses for the message {}",
                            SafeString.objectDescription(message.message));
            }
        } finally {
            if (!messageSentSomewhere)
                statsCollector.messageNotSent();
        }
    }

    @Override
    public void start(final Infrastructure infra) {
        final ClusterInfoSession session = infra.getCollaborator();
        final String nodesDir = infra.getRootPaths().nodesDir;

        checkup = new PersistentTask(LOGGER, isRunning, infra.getScheduler(), RETRY_TIMEOUT) {

            @Override
            public boolean execute() {
                try {
                    // collect up all NodeInfo's known about.
                    session.recursiveMkdir(nodesDir, null, DirMode.PERSISTENT, DirMode.PERSISTENT);
                    final Collection<String> nodeDirs = session.getSubdirs(nodesDir, this);

                    final Set<NodeInformation> alreadySeen = new HashSet<>();
                    // get all of the subdirectories NodeInformations
                    for (final String subdir : nodeDirs) {
                        final NodeInformation ni = (NodeInformation) session.getData(nodesDir + "/" + subdir, null);

                        if (ni == null) {
                            LOGGER.warn("A node directory was empty at " + subdir);
                            return false;
                        }

                        // see if node info is dupped.
                        if (alreadySeen.contains(ni.nodeAddress)) {
                            LOGGER.warn("The node " + ni.nodeAddress + " seems to be registed more than once.");
                            continue;
                        }

                        if (ni.clusterInfoByClusterId.size() == 0) { // it's ALL adaptor so there's no sense in dealing with it
                            LOGGER.trace("NodeInformation {} appears to be only an Adaptor.", ni);
                            continue;
                        }

                        alreadySeen.add(ni);
                    }

                    // check to see if there's new nodes.
                    final ApplicationState.Update ud = outbounds.get().update(alreadySeen, thisNode, thisNodeId);

                    if (!ud.change()) {
                        isReady.set(true);
                        return true; // nothing to update.
                    } else if (LOGGER.isTraceEnabled())
                        LOGGER.trace("Updating for " + thisNodeId);

                    // otherwise we will be making changes so remove the current ApplicationState
                    final ApplicationState obs = outbounds.getAndSet(null); // this can cause instability.
                    try {
                        final ApplicationState newState = obs.apply(ud, tmanager, statsCollector, manager);
                        outbounds.set(newState);
                        isReady.set(true);
                        return true;
                    } catch (final RuntimeException rte) {
                        // if we threw an exception after clearing the outbounds we need to restore it.
                        // This is likely a configuration error so we should probably warn about it.
                        LOGGER.warn("Unexpected exception while applying a topology update", rte);
                        outbounds.set(obs);
                        throw rte;
                    }
                } catch (final ClusterInfoException e) {
                    final String message = "Failed to find outgoing route information. Will retry shortly.";
                    if (LOGGER.isTraceEnabled())
                        LOGGER.debug(message, e);
                    else LOGGER.debug(message);
                    return false;
                }
            }

            @Override
            public String toString() {
                return "find nodes to route to";
            }

        };

        outbounds.set(new ApplicationState(tmanager, thisNode));

        isRunning.set(true);
        checkup.process();
    }

    @Override
    public boolean isReady() {
        if (isReady.get()) {
            final ApplicationState obs = outbounds.get();
            if (obs == null)
                return false;
            if (!manager.isReady()) // this will check the current Routers.
                return false;
            if (!tmanager.isReady())
                return false;

            return true;
        } else return false;
    }

    @Override
    public void stop() {
        synchronized (isRunning) {
            isRunning.set(false);
            final ApplicationState cur = outbounds.getAndSet(null);
            if (cur != null)
                cur.stop();
        }
    }

    // =====================================================================
    // Strictly for testing.
    // =====================================================================
    boolean canReach(final String cluterName, final KeyedMessageWithType message) {
        final ApplicationState cur = outbounds.get();
        if (cur == null)
            return false;
        return cur.canReach(cluterName, message);
    }

    Collection<ContainerAddress> allReachable(final String cluterName) {
        final ApplicationState cur = outbounds.get();
        if (cur == null)
            return new ArrayList<>();
        return cur.allReachable(cluterName);
    }

    String thisNodeId() {
        return thisNodeId;
    }

    NodeStatsCollector getNodeStatCollector() {
        return statsCollector;
    }
    // =====================================================================

}
