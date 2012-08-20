/*
 * Galaxy
 * Copyright (C) 2012 Parallel Universe Software Co.
 * 
 * This file is part of Galaxy.
 *
 * Galaxy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 3 of 
 * the License, or (at your option) any later version.
 *
 * Galaxy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public 
 * License along with Galaxy. If not, see <http://www.gnu.org/licenses/>.
 */
package co.paralleluniverse.galaxy.zookeeper;

import static co.paralleluniverse.galaxy.cluster.DistributedTreeUtil.child;
import static co.paralleluniverse.galaxy.netty.IpConstants.INET_ADDRESS_READER_WRITER;
import static co.paralleluniverse.galaxy.netty.IpConstants.IP_ADDRESS;
import co.paralleluniverse.galaxy.core.AbstractCluster;
import co.paralleluniverse.galaxy.core.RefAllocator;
import co.paralleluniverse.galaxy.core.RefAllocatorSupport;
import co.paralleluniverse.galaxy.core.RootLocker;
import com.google.common.base.Throwables;
import com.netflix.curator.RetryPolicy;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.framework.recipes.atomic.AtomicValue;
import com.netflix.curator.framework.recipes.atomic.DistributedAtomicLong;
import com.netflix.curator.framework.recipes.locks.InterProcessMutex;
import com.netflix.curator.retry.ExponentialBackoffRetry;
import java.beans.ConstructorProperties;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pron
 */
public class ZooKeeperCluster extends AbstractCluster implements RootLocker, RefAllocator {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperCluster.class);
    private static long INITIAL_REF_ID = 0xffffffffL + 1;
    private static final String ROOT_LOCKS = ROOT + "/root_locks";
    private static final String REF_COUNTER = ROOT + "/ref_counter";
    private final String zkConnectString;
    private int sessionTimeoutMs = 15000;
    private int connectionTimeoutMs = 10000;
    private RetryPolicy retryPolicy = new ExponentialBackoffRetry(20, 20);
    private CuratorFramework client;
    private String myNodeName;
    private final RefAllocatorSupport refAllocatorSupport = new RefAllocatorSupport();
    private final ExecutorService refAllocationExecutor = Executors.newFixedThreadPool(1);
    private DistributedAtomicLong refIdCounter;
    private volatile boolean counterReady;

    @ConstructorProperties({"name", "nodeId", "zkConnectString"})
    public ZooKeeperCluster(String name, short nodeId, String zkConnectString) throws Exception {
    	this(name, nodeId, zkConnectString, null);
    }
    
    @ConstructorProperties({"name", "nodeId", "zkConnectString", "hostIp"})
    public ZooKeeperCluster(String name, short nodeId, String zkConnectString, String hostIp) throws Exception {
        super(name, nodeId);
        this.zkConnectString = zkConnectString;

        addNodeProperty(IP_ADDRESS, true, true, INET_ADDRESS_READER_WRITER);
        setNodeProperty(IP_ADDRESS, hostIp == null ? 
        		InetAddress.getLocalHost() : InetAddress.getByName(hostIp));
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        assertDuringInitialization();
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        assertDuringInitialization();
        this.retryPolicy = retryPolicy;
    }

    public void setSessionTimeoutMs(int sessionTimeoutMs) {
        assertDuringInitialization();
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    @Override
    protected void init() throws Exception {
        super.init();

        client = CuratorFrameworkFactory.newClient(zkConnectString, sessionTimeoutMs, connectionTimeoutMs, retryPolicy);
        client.start();

        try {
            client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(ROOT + "/node_names");
        } catch (KeeperException.NodeExistsException e) {
        }

        myNodeName = child(client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(ROOT + "/node_names" + "/node-"));
        LOG.info("Node name is {}, id is {}", myNodeName, myId);
        setName(myNodeName);

        initRefIdCounter();

        final ZooKeeperDistributedTree tree = new ZooKeeperDistributedTree(client);
        setControlTree(tree);

        super.init(); // super.init() must be called after setControlTree()
    }

    private void initRefIdCounter() throws Exception {
        this.refIdCounter = new DistributedAtomicLong(client, REF_COUNTER, retryPolicy);
        AtomicValue<Long> av;

        av = refIdCounter.increment(); // we need this b/c refIdCounter.compareAndSet(0, INITIAL_REF_ID) doesn't work on a newly allocated znode.
        if (!av.succeeded())
            throw new RuntimeException("Error initializing refIdCounter");
        refAllocationExecutor.submit(new Callable<Void>() {

            @Override
            public Void call() throws Exception {
                LOG.info("Waiting for id counter to be set...");
                try {
                    AtomicValue<Long> av;
                    for (;;) {
                        av = refIdCounter.get();
                        if (av.succeeded()) {
                            if (av.postValue() >= INITIAL_REF_ID)
                                break;
                        } else
                            LOG.info("Failed to read counter");
                        Thread.sleep(500);
                    }
                    LOG.info("Id counter set: {}", av.postValue());
                    counterReady = true;
                    refAllocatorSupport.fireCounterReady();
                    return null;
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
            }

        });
    }

    @Override
    public void shutdown() {
        refAllocationExecutor.shutdownNow();
        client.close();

        super.shutdown();
    }

    @Override
    protected boolean isMe(NodeInfoImpl node) {
        return myNodeName.equals(node.getName());
    }

    @Override
    public Object getUnderlyingResource() {
        return client;
    }

    @Override
    public Object lockRoot(int id) {
        try {
            final InterProcessMutex mutex = new InterProcessMutex(client, ROOT_LOCKS + '/' + id);
            mutex.acquire();
            return mutex;
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
    }

    @Override
    public void unlockRoot(Object lock) {
        try {
            final InterProcessMutex mutex = (InterProcessMutex) lock;
            mutex.release();
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
    }

    @Override
    public void addRefAllocationsListener(RefAllocationsListener listener) {
        refAllocatorSupport.addRefAllocationsListener(listener);
        if (counterReady)
            listener.counterReady();
    }

    @Override
    public void removeRefAllocationsListener(RefAllocationsListener listener) {
        refAllocatorSupport.addRefAllocationsListener(listener);
    }

    @Override
    public boolean setCounter(long initialValue) {
        initialValue = Math.max(initialValue, INITIAL_REF_ID);
        LOG.info("Setting ref counter to {}", initialValue);
        try {
            AtomicValue<Long> av;
            long id = 0;
            for (;;) {
                av = refIdCounter.compareAndSet(id, initialValue);
                if (av.succeeded()) {
                    assert av.postValue() == initialValue;
                    LOG.info("Set id counter to {}", initialValue);
                    return true;
                } else if (av.postValue() >= initialValue) {
                    LOG.info("Id counter set by someone else to {}", initialValue);
                    return false;
                } else
                    id = av.preValue();

                Thread.sleep(500);
            }
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void allocateRefs(final int count) {
        refAllocationExecutor.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    LOG.info("Allocating {} IDs", count);
                    final AtomicValue<Long> av = refIdCounter.add((long) count);
                    if (av.succeeded())
                        refAllocatorSupport.fireRefsAllocated(av.preValue(), count);
                    else
                        LOG.error("Allocating ref IDs has failed!");
                } catch (Exception e) {
                    LOG.error("Allocating ref IDs has failed!", e);
                }
            }

        });
    }

}
