 /*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import org.midonet.cluster.data.storage.{ReadOnlyStorage, UpdateValidator}
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, CreateNode, Delete, DeleteNode, MidoOp, Update}
import org.midonet.cluster.services.c3po.translators.PortManager._
import org.midonet.cluster.util.DhcpUtil.asRichDhcp
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.ARP
import org.midonet.util.concurrent.toFutureOps

object PortTranslator {
    private def egressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_INBOUND"

    private def ingressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_OUTBOUND"
}

class PortTranslator(protected val storage: ReadOnlyStorage,
                     protected val pathBldr: PathBuilder)
        extends NeutronTranslator[NeutronPort]
        with ChainManager with PortManager with RouteManager with RuleManager {
    import org.midonet.cluster.services.c3po.translators.PortTranslator._

    override protected def translateCreate(nPort: NeutronPort): MidoOpList = {
        // Floating IP ports and ports on uplink networks have no corresponding
        // Midonet port.
        if (isFloatingIpPort(nPort) || isOnUplinkNetwork(nPort)) return List()

        // All other ports have a corresponding Midonet network (bridge) port.
        val midoPortBldr = translateNeutronPort(nPort)

        val portId = nPort.getId
        val midoOps = new MidoOpListBuffer
        val portContext = initPortContext
        if (isVifPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            midoPortBldr.setInboundFilterId(inChainId(portId))
            midoPortBldr.setOutboundFilterId(outChainId(portId))

            // Add MAC->port map entry.
            midoOps += CreateNode(pathBldr.getBridgeMacPortEntryPath(nPort))

            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
            updateSecurityBindings(nPort, null, midoPortBldr, portContext)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpServer)
            midoOps ++= configureMetaDataService(nPort)
        }

        addMidoOps(portContext, midoOps)
        midoOps += Create(midoPortBldr.build)
        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val nPort = storage.get(classOf[NeutronPort], id).await()

        val midoOps = new MidoOpListBuffer

        // Floating IP ports have no Midonet equivalent. Neither do ports on
        // uplink networks, but to determine whether that's the case here, we'd
        // have to make a round trip to Zookeeper. Better just to return the
        // DeleteOp, and Zoom's delete idempotency will handle it.
        if (!isFloatingIpPort(nPort))
            midoOps += Delete(classOf[Port], id)

        if (isRouterGatewayPort(nPort)) {
            midoOps += Delete(classOf[Port],
                              RouterTranslator.tenantGwPortId(id))
        } else if (isRouterInterfacePort(nPort)) {
            // Delete the peer router port.
            midoOps += Delete(classOf[Port],
                              routerInterfacePortPeerId(nPort.getId))
            // Via Neutron API no chains are attached to those internal Network
            // and Router Ports. We can assert that if needed, but it seems
            // better to avoid extra ZK round trips.
        }

        val portContext = initPortContext
        if (isVifPort(nPort)) { // It's a VIF port.
            val mPort = storage.get(classOf[Port], id).await()
            // Delete old DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            deleteSecurityBindings(nPort, mPort, portContext)
            midoOps += DeleteNode(pathBldr.getBridgeMacPortEntryPath(nPort))
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              delDhcpServer)
            midoOps ++= deleteMetaDataServiceRoute(nPort)
        }
        addMidoOps(portContext, midoOps)

        midoOps.toList
    }

    override protected def translateUpdate(nPort: NeutronPort): MidoOpList = {
        // Uplink networks don't exist in Midonet.
        if (isOnUplinkNetwork(nPort)) return List()

        val midoOps = new MidoOpListBuffer

        // It is assumed that the fixed IPs assigned to a Neutron Port will not
        // be changed.
        val portId = nPort.getId
        val mPort = storage.get(classOf[Port], portId).await()
        if (portChanged(nPort, mPort)) {
            // TODO: Is it okay not to check the port type? I assume Neutron
            // won't tell us to do something we shouldn't.
            val bldr = mPort.toBuilder.setAdminStateUp(nPort.getAdminStateUp)
            if (hasBinding(nPort)) {
                bldr.setHostId(nPort.getHostId)
                bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
            } else {
                bldr.clearHostId().clearInterfaceName()
            }

            midoOps += Update(bldr.build())
        }

        if (isVifPort(nPort)) { // It's a VIF port.
            val portContext = initPortContext
            val oldNPort = storage.get(classOf[NeutronPort], portId).await()
            // Delete old DHCP host entries
            updateDhcpEntries(oldNPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
            updateSecurityBindings(nPort, oldNPort, mPort, portContext)
            addMidoOps(portContext, midoOps)
            midoOps ++= macTableUpdateOps(oldNPort, nPort)
        }
        // TODO if a DHCP port, assert that the fixed IPs haven't changed.
        // TOOD A VIF port may be re-purposed as a Router Interface port,
        //      however, NeutronPlugin Python code do not handle the scenario
        //      correctly yet. Implement later.

        midoOps.toList
    }

    /* A container class holding context associated with a Neutron Port CRUD. */
    private case class PortContext(
            midoDhcps: mutable.Map[UUID, Dhcp.Builder],
            inRules: ListBuffer[MidoOp[Rule]],
            outRules: ListBuffer[MidoOp[Rule]],
            chains: ListBuffer[MidoOp[Chain]],
            updatedIpAddrGrps: ListBuffer[MidoOp[IPAddrGroup]])

    private def initPortContext =
        PortContext(mutable.Map[UUID, Dhcp.Builder](),
                    ListBuffer[MidoOp[Rule]](),
                    ListBuffer[MidoOp[Rule]](),
                    ListBuffer[MidoOp[Chain]](),
                    ListBuffer[MidoOp[IPAddrGroup]]())


    private def addMidoOps(portContext: PortContext,
                           midoOps: MidoOpListBuffer) {
            midoOps ++= portContext.midoDhcps.values.map(d => Update(d.build))
            midoOps ++= portContext.inRules ++ portContext.outRules
            midoOps ++= portContext.chains
            midoOps ++= portContext.updatedIpAddrGrps
    }

    // There's no binding unless both hostId and interfaceName are set.
    private def hasBinding(np: NeutronPort): Boolean =
        np.hasHostId && np.hasProfile && np.getProfile.hasInterfaceName
    private def hasBinding(p: Port): Boolean = p.hasHostId && p.hasInterfaceName

    /**
     * Returns true if a port has changed in a way relevant to a port update,
     * i.e. whether the admin state or host binding has changed.
     */
    private def portChanged(newPort: NeutronPort, curPort: Port): Boolean = {
        if (newPort.getAdminStateUp != curPort.getAdminStateUp ||
            hasBinding(newPort) != hasBinding(curPort)) return true

        hasBinding(newPort) &&
            (newPort.getHostId != curPort.getHostId ||
             newPort.getProfile.getInterfaceName != curPort.getInterfaceName)
    }

    /* Update DHCP configuration by applying the given updateFun. */
    private def updateDhcpEntries(
            nPort: NeutronPort,
            subnetCache: mutable.Map[UUID, Dhcp.Builder],
            updateFun: (Dhcp.Builder, String, IPAddress) => Unit) {
        for (ipAlloc <- nPort.getFixedIpsList.asScala) {
            val subnet = subnetCache.getOrElseUpdate(
                    ipAlloc.getSubnetId,
                    storage.get(classOf[Dhcp],
                                ipAlloc.getSubnetId).await().toBuilder)
            val mac = nPort.getMacAddress
            val ipAddress = ipAlloc.getIpAddress
            updateFun(subnet, mac, ipAddress)
        }
    }

    /* Adds a host entry with the given mac / IP address pair to DHCP. */
    private def addDhcpHost(
            dhcp: Dhcp.Builder, mac: String, ipAddr: IPAddress) {
        dhcp.addHostsBuilder()
            .setMac(mac)
            .setIpAddress(ipAddr)
    }

    /* Deletes a host entry with the given mac / IP address pair in DHCP. */
    private def delDhcpHost(
            dhcp: Dhcp.Builder, mac: String, ipAddr: IPAddress) {
        val remove = dhcp.getHostsList.asScala.indexWhere(
            h => h.getMac == mac && h.getIpAddress == ipAddr)
        if (remove >= 0) dhcp.removeHosts(remove)
    }

    /* Configures the DHCP server and an OPT 121 route to it with the given IP
     * address (the mac is actually not being used here). */
    private def addDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              ipAddr: IPAddress): Unit = if (dhcp.isIpv4) {
        dhcp.setServerAddress(ipAddr)
        val opt121 = dhcp.addOpt121RoutesBuilder()
        opt121.setDstSubnet(RouteManager.META_DATA_SRVC)
              .setGateway(ipAddr)
    }

    /* Removes the DHCP server and OPT 121 route configurations with the given
     * IP address from DHCP (the mac is actually not being used here). */
    private def delDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              nextHopGw: IPAddress): Unit = if (dhcp.isIpv4) {
        dhcp.clearServerAddress()
        val route = dhcp.getOpt121RoutesOrBuilderList.asScala
                        .indexWhere(isMetaDataSvrOpt121Route(_, nextHopGw))
        if (route >= 0) dhcp.removeOpt121Routes(route)
    }

    /* Create chains, rules and IP Address Groups associated with nPort. */
    private def updateSecurityBindings(nPort: NeutronPort,
                                       nPortOld: NeutronPort,
                                       mPort: PortOrBuilder,
                                       portCtx: PortContext) {
        val portId = nPort.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val mac = nPort.getMacAddress

        // Create an IP spoofing protection rule.
        for (dhcp <- portCtx.midoDhcps.values if dhcp.getHostsCount > 0) {
            // NOTE: if a port belongs to more than 1 subnet, the drop rules
            // that would be created below would drop all packets. Currently
            // we don't "handle" more than 1 IP address per port, so there
            // should be no problem, but this can potentially cause problems.
            portCtx.inRules += Create(dropRuleBuilder(inChainId)
                                      .setNwSrcIp(dhcp.getSubnetAddress)
                                      .setNwSrcInv(true)
                                      .setDlType(dhcp.etherType).build)

            // TODO If a port is attached to an external NeutronNetwork,
            // add a route to the provider router.
        }

        // Create return flow rules.
        portCtx.outRules += Create(returnFlowRule(outChainId))
        // MAC spoofing protection
        portCtx.inRules += Create(dropRuleBuilder(inChainId)
                                  .setDlSrc(mac)
                                  .setInvDlSrc(true).build)

        // Create return flow rules matching for inbound chain.
        portCtx.inRules += Create(returnFlowRule(inChainId))

        // Add jump rules to corresponding inbound / outbound chains of IP
        // Address Groups (Neutron's Security Groups) that the port belongs to.
        for (sgId <- nPort.getSecurityGroupsList.asScala) {
            val ipAddrGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            // Jump rules to inbound / outbound chains of IP Address Groups
            portCtx.inRules += Create(jumpRule(inChainId,
                                               ipAddrGrp.getInboundChainId))
            portCtx.outRules += Create(jumpRule(outChainId,
                                                ipAddrGrp.getOutboundChainId))

            // Compute IP addresses that were removed from the IP Address Group
            // or added to it.
            val updatedIpAddrG = ipAddrGrp.toBuilder
            val ips = nPort.getFixedIpsList.asScala.map(_.getIpAddress)
            val oldIps = if (nPortOld != null) {
                    nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
                } else List()
            val removed = oldIps diff ips
            var added = ips diff oldIps
            for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                if (removed.contains(ipPorts.getIpAddress)) {
                    val idx = ipPorts.getPortIdsList.asScala
                                                    .indexOf(portId)
                    if (idx >= 0) ipPorts.removePortIds(idx)
                } else  if (added contains ipPorts.getIpAddress) {
                    // Exclude IPs that are already in IP Address Groups.
                    ipPorts.addPortIds(portId)
                    added -= ipPorts.getIpAddress
                }
            }
            for (newIp <- added)
                updatedIpAddrG.addIpAddrPortsBuilder()
                              .setIpAddress(newIp)
                              .addPortIds(portId)
            portCtx.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }

        // Drop non-ARP traffic that wasn't accepted by earlier rules.
        portCtx.inRules += Create(dropRuleBuilder(inChainId)
                                  .setDlType(ARP.ETHERTYPE)
                                  .setInvDlType(true).build)
        portCtx.outRules += Create(dropRuleBuilder(outChainId)
                                   .setDlType(ARP.ETHERTYPE)
                                   .setInvDlType(true).build)

        // Create in/outbound chains with the IDs of the above rules.
        val inChain = newChain(inChainId, egressChainName(portId),
                               toRuleIdList(portCtx.inRules))
        val outChain = newChain(outChainId, ingressChainName(portId),
                                toRuleIdList(portCtx.outRules))

        if (nPortOld != null) { // Update
            portCtx.chains += (Update(inChain), Update(outChain))

            val iChain = storage.get(classOf[Chain], inChainId).await()
            portCtx.inRules ++= iChain.getRuleIdsList.asScala
                                      .map(Delete(classOf[Rule], _))
            val oChain = storage.get(classOf[Chain], outChainId).await()
            portCtx.outRules ++= oChain.getRuleIdsList.asScala
                                       .map(Delete(classOf[Rule], _))
        } else { // Create
            portCtx.chains += (Create(inChain), Create(outChain))
        }
    }

    /* Delete chains, rules and IP Address Groups associated with nPortOld. */
    private def deleteSecurityBindings(nPortOld: NeutronPort,
                                       mPort: Port,
                                       portContext: PortContext) {
        val portId = nPortOld.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val iChain = storage.get(classOf[Chain], inChainId).await()
        portContext.inRules ++= iChain.getRuleIdsList.asScala
                                   .map(Delete(classOf[Rule], _))
        val oChain = storage.get(classOf[Chain], outChainId).await()
        portContext.outRules ++= oChain.getRuleIdsList.asScala
                                    .map(Delete(classOf[Rule], _))
        portContext.chains += (Delete(classOf[Chain], inChainId),
                            Delete(classOf[Chain], outChainId))

        // Remove the fixed IPs from IP Address Groups
        for (sgId <- nPortOld.getSecurityGroupsList.asScala) {
            val ipAddrG = storage.get(classOf[IPAddrGroup], sgId).await()
            val updatedIpAddrG = ipAddrG.toBuilder
            val oldIps = nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
            for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                if (oldIps.contains(ipPorts.getIpAddress)) {
                    val idx = ipPorts.getPortIdsList.asScala
                                                    .indexWhere(_ == portId)
                    if (idx >= 0) ipPorts.removePortIds(idx)
                }
            }
            portContext.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }
    }

    /* If the first fixed IP address is configured with a gateway IP address,
     * create a route to Meta Data Service.*/
    private def configureMetaDataService(nPort: NeutronPort): MidoOpList =
        findGateway(nPort).toList.flatMap { gateway =>
            val route = newMetaDataServiceRoute(
                srcSubnet = gateway.nextHopDhcp.getSubnetAddress,
                nextHopPortId = gateway.peerRouterPortId,
                nextHopGw = gateway.nextHop)
            List(Create(route))
        }

    /* If the first fixed IP address is configured with a gateway IP address,
     * delete a route to Meta Data Service from the gateway router.*/
    private def deleteMetaDataServiceRoute(nPort: NeutronPort): MidoOpList = {
        val gateway = findGateway(nPort).getOrElse(return List())
        val port = storage.get(classOf[Port], gateway.peerRouterPortId).await()
        val routeIds = port.getRouteIdsList.asScala
        val routes = routeIds.map(storage.get(classOf[Route], _)).map(_.await())
        val route = routes.find(isMetaDataSvrRoute(_, gateway.nextHop))
            .getOrElse(return List())
        List(Delete(classOf[Route], route.getId))
    }

    /* The next hop gateway context for Neutron Port. */
    private case class Gateway(nextHop: IPAddress, nextHopDhcp: Dhcp,
                               peerRouterPortId: UUID, peerRouter: Router)

    /* Find gateway router & router port configured with the port's fixed IP. */
    private def findGateway(nPort: NeutronPort): Option[Gateway] =
    if (nPort.getFixedIpsCount == 0) None else {
        val nextHopGateway = nPort.getFixedIps(0).getIpAddress
        val nextHopGatewaySubnetId = nPort.getFixedIps(0).getSubnetId

        val dhcp = storage.get(classOf[Dhcp], nextHopGatewaySubnetId).await()
        if (!dhcp.hasRouterGwPortId) return None

        val rtrGwPort = storage.get(classOf[Port],
                                    dhcp.getRouterGwPortId).await()
        val router = storage.get(classOf[Router], rtrGwPort.getRouterId).await()
        Some(Gateway(nextHopGateway, dhcp, rtrGwPort.getId, router))
    }

    private def translateNeutronPort(nPort: NeutronPort): Port.Builder = {
        val bldr = Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)

        if (hasBinding(nPort)) {
            bldr.setHostId(nPort.getHostId)
            bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
        }

        bldr
    }

    private def macTableUpdateOps(oldPort: NeutronPort,
                                  newPort: NeutronPort): MidoOpList = {
        val ops = new MidoOpListBuffer
        if (newPort.getMacAddress != oldPort.getMacAddress) {
            if (oldPort.hasMacAddress)
                ops += DeleteNode(pathBldr.getBridgeMacPortEntryPath(oldPort))
            if (newPort.hasMacAddress)
                ops += CreateNode(pathBldr.getBridgeMacPortEntryPath(newPort))
        }
        ops.toList
    }
}

private[translators] object PortUpdateValidator extends UpdateValidator[Port] {
    override def validate(oldPort: Port, newPort: Port): Port = {
        newPort.toBuilder
            .addAllRouteIds(oldPort.getRouteIdsList)
            .addAllRuleIds(oldPort.getRuleIdsList)
            .build()
    }
}
