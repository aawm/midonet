/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class Bridge {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant tenant;
        DtoBridge bridge;

        public Builder(MidolmanMgmt mgmt, DtoTenant tenant) {
            this.mgmt = mgmt;
            this.tenant = tenant;
            this.bridge = new DtoBridge();
        }

        public Builder setName(String name) {
            bridge.setName(name);
            return this;
        }

        public Bridge build() {
            if (bridge.getName().isEmpty() || bridge.getName() == null)
                throw new IllegalArgumentException("Cannot create a "
                        + "bridge with a null or empty name.");
            return new Bridge(mgmt, mgmt.addBridge(tenant, bridge));

        }
    }

    MidolmanMgmt mgmt;
    DtoBridge dto;

    Bridge(MidolmanMgmt mgmt, DtoBridge bridge) {
        this.mgmt = mgmt;
        this.dto = bridge;
    }

    public BridgePort.Builder addPort() {
        return new BridgePort.Builder(mgmt, dto);
    }

    public LogicalBridgePort.Builder addLinkPort() {
        return new LogicalBridgePort.Builder(mgmt, dto);
    }

    public Subnet.Builder newDhcpSubnet() {
        return new Subnet.Builder(this.mgmt, this.dto);
    }
    public UUID getId(){
        return dto.getId();
    }

    public void setInboundFilter(UUID id) {
        dto.setInboundFilterId(id);
        mgmt.updateBridge(dto);
    }

    public void delete() {
        mgmt.delete(dto.getUri());
    }
}
