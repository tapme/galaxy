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
import co.paralleluniverse.galaxy.cluster.NodeChangeListener;
import co.paralleluniverse.galaxy.cluster.NodeInfo;
import co.paralleluniverse.galaxy.cluster.ReaderWriters;
import co.paralleluniverse.galaxy.core.Comm;
import co.paralleluniverse.galaxy.core.Message;
import co.paralleluniverse.galaxy.core.MessageReceiver;
import static co.paralleluniverse.galaxy.netty.IpConstants.*;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import java.beans.ConstructorProperties;
import java.net.InetAddress;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ServerChannel;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pron
 */
final class TcpServerServerComm extends AbstractTcpServer implements Comm {
    private static final Logger LOG = LoggerFactory.getLogger(TcpServerServerComm.class);
    private MessageReceiver receiver;

    @ConstructorProperties({"name", "cluster", "port"})
    public TcpServerServerComm(String name, Cluster cluster, int port) throws Exception {
        this(name, cluster, port, null, null);
    }
    
    @ConstructorProperties({"name", "cluster", "port", "hostIp"})
    public TcpServerServerComm(String name, Cluster cluster, int port, String hostIp) throws Exception {
        this(name, cluster, port, null, hostIp);
    }

    TcpServerServerComm(String name, final Cluster cluster, int port, final ChannelHandler testHandler, String hostIp) throws Exception {
        super(name, cluster, new ChannelGroup(), port, testHandler);
        
        InetAddress ip = hostIp == null ? InetAddress.getLocalHost() : InetAddress.getByName(hostIp);

        cluster.addNodeProperty(IP_ADDRESS, true, true, INET_ADDRESS_READER_WRITER);
        cluster.setNodeProperty(IP_ADDRESS, ip);
        cluster.addNodeProperty(IP_SERVER_PORT, false, true, ReaderWriters.INTEGER);
        cluster.setNodeProperty(IP_SERVER_PORT, port);

        cluster.addNodeChangeListener(new NodeChangeListener() {
            @Override
            public void nodeAdded(short id) {
            }

            @Override
            public void nodeSwitched(short id) {
                final Channel channel = getChannels().get(id);
                if (channel != null) {
                    LOG.info("Closing channel for switched node {}", id);
                    channel.close();
                }
            }

            @Override
            public void nodeRemoved(short id) {
                final Channel channel = getChannels().get(id);
                if (channel != null) {
                    LOG.info("Closing channel for removed node {}", id);
                    channel.close();
                }
            }
        });
    }

    @Override
    public void start(boolean master) {
        bind();
    }
    
    @Override
    protected ChannelGroup getChannels() {
        return (ChannelGroup) super.getChannels();
    }

    @Override
    public void setReceiver(MessageReceiver receiver) {
        assertDuringInitialization();
        this.receiver = receiver;
    }

    @Override
    public void send(Message message) {
        if (!message.isResponse())
            message.setMessageId(nextMessageId()); // TODO: possible pitfall: b/c this method is not synchronized, two threads may run it concurrently, one would get a smaller id but the other would put the message in a queue first - broken invariant!
        LOG.debug("Send {}", message);
        final Channel ch = getChannels().get(message.getNode());
        if (ch == null) {
            LOG.warn("No open channel found for node {}", message.getNode());
            return;
        }
        ch.write(message);
    }

    @Override
    protected void receive(ChannelHandlerContext ctx, Message message) {
        receiver.receive(message);
    }

    private static class ChannelGroup extends DefaultChannelGroup {
        private final BiMap<Short, Channel> channels = Maps.synchronizedBiMap((HashBiMap) HashBiMap.create());

        public ChannelGroup(String name) {
            super(name);
        }

        public ChannelGroup() {
        }

        @Override
        public boolean add(Channel channel) {
            if (channel instanceof ServerChannel)
                return super.add(channel);
            else {
                final NodeInfo node = ChannelNodeInfo.nodeInfo.get(channel);
                if (node == null) {
                    LOG.warn("Received connection from an unknown address {}.", channel.getRemoteAddress());
                    throw new RuntimeException("Unknown node for address " + channel.getRemoteAddress());
                }
                final short nodeId = node.getNodeId();
                if (channels.containsKey(nodeId)) {
                    LOG.warn("Received connection from address {} of node {}, but this node is already connected.", channel.getRemoteAddress(), nodeId);
                    throw new RuntimeException("Node " + nodeId + " already connected.");
                }
                final boolean added = super.add(channel);
                if (added)
                    channels.put(nodeId, channel);
                return added;
            }
        }

        @Override
        public boolean remove(Object o) {
            final Channel channel = (Channel) o;
            final boolean removed = super.remove(o);
            if (removed)
                channels.inverse().remove(channel);
            ChannelNodeInfo.nodeInfo.remove(channel);
            return removed;
        }

        @Override
        public void clear() {
            super.clear();
            channels.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof Short)
                return channels.containsKey((Short) o);
            else
                return super.contains(o);
        }

        public Channel get(short node) {
            return channels.get(node);
        }
    }
}
