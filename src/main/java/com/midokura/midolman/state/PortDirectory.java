package com.midokura.midolman.state;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.util.Net;

public class PortDirectory {

    public static abstract class PortConfig {

        private PortConfig(UUID device_id) {
            super();
            this.device_id = device_id;
        }
        // Default constructor for the Jackson deserialization.
        PortConfig() { super(); }
        public UUID device_id;
    }

    public static class BridgePortConfig extends PortConfig {
        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }
        // Default constructor for the Jackson deserialization.
        private BridgePortConfig() { super(); }
    
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof BridgePortConfig))
                return false;
            BridgePortConfig port = (BridgePortConfig) other;
            return this.device_id.equals(port.device_id);
        }
    }

    public static abstract class RouterPortConfig extends PortConfig {
        public int nwAddr;
        public int nwLength;
        public transient int portAddr;
        // Routes are stored in a ZK sub-directory. Don't serialize them.        
        public transient Set<Route> routes;
    
        public RouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes) {
            super(device_id);
            this.nwAddr = networkAddr;
            this.nwLength = networkLength;
            this.portAddr = portAddr;
            this.routes = routes;
        }
    
        // Default constructor for the Jackson deserialization.
        public RouterPortConfig() { super(); }
    
        // Setter and getter for the transient property.
        public String getPortAddr() {
            return Net.convertIntAddressToString(this.portAddr);
        }
        public void setPortAddr(String addr) {
            this.portAddr = Net.convertStringAddressToInt(addr);
        }
        
        public Set<Route> getRoutes() { return routes; }
        public void setRoutes(Set<Route> routes) { this.routes = routes; }
    }

    public static class LogicalRouterPortConfig extends RouterPortConfig {
        public UUID peer_uuid;
        
        public LogicalRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                UUID peer_uuid) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
            this.peer_uuid = peer_uuid;
        }
    
        // Default constructor for the Jackson deserialization.
        public LogicalRouterPortConfig() { super(); }
    
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof LogicalRouterPortConfig))
                return false;
            LogicalRouterPortConfig port = (LogicalRouterPortConfig) other;
            return device_id.equals(port.device_id) && nwAddr == port.nwAddr
                    && nwLength == port.nwLength
                    && peer_uuid.equals(port.peer_uuid)
                    && portAddr == port.portAddr
                    && getRoutes().equals(port.getRoutes());
        }
    }

    public static class MaterializedRouterPortConfig extends RouterPortConfig {
        public int localNwAddr;
        public int localNwLength;
        public transient Set<BGP> bgps;
        
        public MaterializedRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                int localNetworkAddr, int localNetworkLength, Set<BGP> bgps) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
            this.localNwAddr = localNetworkAddr;
            this.localNwLength = localNetworkLength;
            setBgps(bgps);
        }
    
        // Default constructor for the Jackson deserialization
        public MaterializedRouterPortConfig() { super(); }
    
        // Getter and setter for the Jackson deserialization
        public Set<BGP> getBgps() { return bgps; }
        public void setBgps(Set<BGP> bgps) { this.bgps = bgps; }
    
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof MaterializedRouterPortConfig))
                return false;
            MaterializedRouterPortConfig port = MaterializedRouterPortConfig.class
                    .cast(other);
            return device_id.equals(port.device_id) && nwAddr == port.nwAddr
                    && nwLength == port.nwLength && portAddr == port.portAddr
                    && getRoutes().equals(port.getRoutes())
                    && getBgps().equals(port.getBgps())
                    && localNwAddr == port.localNwAddr
                    && localNwLength == port.localNwLength;
        }
    }

    public static Random random = new Random();
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    static {
        objectMapper.setVisibilityChecker(
            objectMapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }

    Directory dir;

    public PortDirectory(Directory dir) {
        this.dir = dir;
    }

    public static byte[] portToBytes(PortDirectory.PortConfig port) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream out = new BufferedOutputStream(bos);
        JsonGenerator jsonGenerator =
            jsonFactory.createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(port);
        return bos.toByteArray();
    }

    
    public void addPort(UUID portId, PortDirectory.PortConfig port) throws IOException,
            KeeperException, InterruptedException {
        if (!(port instanceof PortDirectory.BridgePortConfig
                || port instanceof PortDirectory.LogicalRouterPortConfig || port instanceof PortDirectory.MaterializedRouterPortConfig))
            throw new IllegalArgumentException("Unrecognized port type.");
        byte[] data = portToBytes(port);
        dir.add("/" + portId.toString(), data, CreateMode.PERSISTENT);
        if (port instanceof PortDirectory.RouterPortConfig) {
            String path = new StringBuilder("/").append(portId.toString())
                    .append("/routes").toString();
            dir.add(path, null, CreateMode.PERSISTENT);
            for (Route rt : ((PortDirectory.RouterPortConfig) port).routes) {
                dir.add(path + "/" + rt.toString(), null, CreateMode.PERSISTENT);
            }            
        }
    }

    public boolean exists(UUID portId) throws KeeperException,
            InterruptedException {
        return dir.has("/" + portId.toString());
    }

    public void addRoutes(UUID portId, Set<Route> routes) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        PortDirectory.PortConfig port = getPortConfigNoRoutes(portId, null);
        if (!(port instanceof PortDirectory.RouterPortConfig))
            throw new IllegalArgumentException(
                    "Routes may only be added to a Router port");
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        for (Route rt : routes)
            dir.add(routesPath + "/" + rt.toString(), null,
                    CreateMode.PERSISTENT);
    }

    public void removeRoutes(UUID portId, Set<Route> routes)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        PortDirectory.PortConfig port = getPortConfigNoRoutes(portId, null);
        if (!(port instanceof PortDirectory.RouterPortConfig))
            throw new IllegalArgumentException(
                    "Routes may only be removed from a Router port");
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        for (Route rt : routes)
            dir.delete(routesPath + "/" + rt.toString());
    }

    public Set<Route> getRoutes(UUID portId, Runnable routesWatcher)
            throws KeeperException, InterruptedException {
        String path = new StringBuilder("/").append(portId.toString()).append(
                "/routes").toString();
        Set<String> rtStrings = dir.getChildren(path, routesWatcher);
        Set<Route> routes = new HashSet<Route>();
        for (String rtStr : rtStrings)
            routes.add(Route.fromString(rtStr));
        return routes;
    }

    public void updatePort(UUID portId, PortDirectory.PortConfig newPort) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        PortDirectory.PortConfig oldPort = getPortConfig(portId, null, null);
        if (oldPort.getClass() != newPort.getClass())
            throw new IllegalArgumentException(
                    "Cannot change a port's type without first deleting it.");
        byte[] portData = portToBytes(newPort);
        dir.update("/" + portId.toString(), portData);
        if (newPort instanceof PortDirectory.RouterPortConfig) {
            PortDirectory.RouterPortConfig newRtrPort = PortDirectory.RouterPortConfig.class.cast(newPort);
            PortDirectory.RouterPortConfig oldRtrPort = PortDirectory.RouterPortConfig.class.cast(oldPort);
            String routesPath = new StringBuilder("/")
                    .append(portId.toString()).append("/routes").toString();
            for (Route rt : newRtrPort.routes) {
                if (!oldRtrPort.routes.contains(rt))
                    dir.add(routesPath + "/" + rt.toString(), null,
                            CreateMode.PERSISTENT);
            }
            for (Route rt : oldRtrPort.routes) {
                if (!newRtrPort.routes.contains(rt))
                    dir.delete(routesPath + "/" + rt.toString());
            }
        }
    }

    public PortDirectory.PortConfig getPortConfigNoRoutes(UUID portId, Runnable portWatcher)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        byte[] data = dir.get("/" + portId.toString(), portWatcher);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        InputStream in = new BufferedInputStream(bis);
        JsonParser jsonParser =
            jsonFactory.createJsonParser(new InputStreamReader(in));
        PortDirectory.PortConfig port = jsonParser.readValueAs(PortDirectory.PortConfig.class);
        return port;
    }

    public PortDirectory.PortConfig getPortConfig(UUID portId, Runnable portWatcher,
            Runnable routesWatcher) throws IOException, ClassNotFoundException,
            KeeperException, InterruptedException {
        PortDirectory.PortConfig port = getPortConfigNoRoutes(portId, portWatcher);
        if (port instanceof PortDirectory.RouterPortConfig) {
            ((PortDirectory.RouterPortConfig) port).routes = getRoutes(portId, routesWatcher);
        } else if (routesWatcher != null)
            throw new IllegalArgumentException(
                    "Can't watch routes on a bridge port");
        return port;
    }

    public void deletePort(UUID portId) throws KeeperException,
            InterruptedException {
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        try {
            Set<String> routes = dir.getChildren(routesPath, null);
            for (String rt : routes)
                dir.delete(routesPath + "/" + rt);
            dir.delete(routesPath);
        } catch (KeeperException.NoNodeException e) {
            // Ignore the exception - the port may not have routes.
        }
        dir.delete("/" + portId.toString());
    }
}
