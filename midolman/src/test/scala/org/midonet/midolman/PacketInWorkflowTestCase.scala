/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DatapathController.PacketIn
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import org.midonet.cluster.data.host.Host
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowKeys
import org.midonet.packets.{IntIPv4, MAC, Packets}


@RunWith(classOf[JUnitRunner])
class PacketInWorkflowTestCase extends MidolmanTestCase {

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def testDatapathPacketIn() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val vifPort = Ports.materializedBridgePort(bridge)
        vifPort.setId(clusterDataClient().portsCreate(vifPort))

        materializePort(vifPort, host, "port")

        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])

        initializeDatapath() should not be (null)

        requestOfType[DatapathController.DatapathReady](flowProbe()).datapath should not be (null)
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val portNo = dpController().underlyingActor.ifaceNameToDpPort("port").getPortNo
        triggerPacketIn(
            new Packet()
                .setData(
                    Packets.udp(
                        MAC.fromString("10:10:10:10:10:10"),
                        MAC.fromString("10:10:10:10:10:11"),
                        IntIPv4.fromString("192.168.100.1"),
                        IntIPv4.fromString("192.168.200.1"),
                        100, 100, new Array[Byte](0)
                    ).serialize())
                .setMatch(new FlowMatch().addKey(FlowKeys.inPort(portNo))))

        val packetIn = fishForRequestOfType[PacketIn](dpProbe())

        packetIn should not be null
        packetIn.cookie should not be None
        packetIn.wMatch should not be null

        val packetInMsg = requestOfType[PacketIn](simProbe())

        packetInMsg.wMatch should not be null
        packetInMsg.wMatch.getInputPortUUID should be (vifPort.getId)
    }
}