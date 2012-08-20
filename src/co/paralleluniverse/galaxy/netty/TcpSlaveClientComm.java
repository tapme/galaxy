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
package co.paralleluniverse.galaxy.netty;

import co.paralleluniverse.galaxy.Cluster;
import co.paralleluniverse.galaxy.cluster.NodeInfo;
import co.paralleluniverse.galaxy.cluster.ReaderWriters;
import co.paralleluniverse.galaxy.cluster.SlaveConfigurationListener;
import co.paralleluniverse.galaxy.core.Backup;
import co.paralleluniverse.galaxy.core.Message;
import static co.paralleluniverse.galaxy.netty.IpConstants.*;
import java.beans.ConstructorProperties;
import java.net.InetAddress;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pron
 */
class TcpSlaveClientComm extends AbstractTcpClient {

    private static final Logger LOG = LoggerFactory.getLogger(TcpSlaveClientComm.class);
    private Backup backup;

    @ConstructorProperties({"name", "cluster"})
    public TcpSlaveClientComm(String name, Cluster cluster) throws Exception {
        super(name, cluster, IP_SLAVE_PORT);

        cluster.addNodeProperty(IP_SLAVE_PORT, true, false, ReaderWriters.INTEGER);

        cluster.addSlaveConfigurationListener(scListener);
    }

    private final SlaveConfigurationListener scListener = new SlaveConfigurationListener() {

        @Override
        public void newMaster(NodeInfo node) {
            if (!node.equals(getCluster().getMyNodeInfo())) {
                LOG.info("New master ({})! Reconnecting.", node.getName());
                reconnect(node.getName());
            } else
                LOG.debug("It appears I'm the new master, and switchToMaster should come soo. Not connecting to myself");
        }

        @Override
        public void slaveAdded(NodeInfo node) {
        }

        @Override
        public void slaveRemoved(NodeInfo node) {
        }

    };

//    @Override
//    protected ChannelPipeline getPipeline() throws Exception {
//        final ChannelPipeline pipeline = super.getPipeline();
//        pipeline.addLast("diconnect", new SimpleChannelUpstreamHandler() {
//            @Override
//            public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
//                super.channelDisconnected(ctx, e);
//                LOG.info("Channel disconnected. Removing as slave candidate.");
//                getCluster().goOffline();
//            }
//        });
//        return pipeline;
//    }
    @Override
    protected void postInit() throws Exception {
        super.postInit();
    }

    @Override
    protected void init() throws Exception {
        super.init();
    }

    @Override
    protected void available(boolean value) {
        super.available(value);
    }

    @Override
    public void joinedCluster() {
        super.joinedCluster();

        final NodeInfo master = getCluster().getMaster(getCluster().getMyNodeId());
        if (master != null) {
            LOG.info("Connecting to master node {}", master.getName());
            reconnect(master.getName());
        }
    }

    @Override
    protected void start(boolean master) {
        if (master) {
            LOG.info("Master node. Shutting down slave client.");
            shutdown();
            return;
        }

        final String myMaster = getCluster().getMyMaster().getName();
        assert myMaster != null;

        if (!myMaster.equals(getNodeName())) {
            LOG.info("Re-connecting to master node {}", myMaster);
            reconnect(myMaster);
        }
    }

    @Override
    public void shutdown() {
        getCluster().removeSlaveConfigurationListener(scListener);

        super.shutdown();
    }

    @Override
    public void switchToMaster() {
        super.switchToMaster();
        LOG.info("Switched to master. Shutting down slave client.");
        shutdown();
    }

    public void setBackup(Backup backup) {
        assertDuringInitialization();
        this.backup = backup;
    }

    @Override
    protected void receive(ChannelHandlerContext ctx, Message message) {
        backup.receive(message);
    }

}
