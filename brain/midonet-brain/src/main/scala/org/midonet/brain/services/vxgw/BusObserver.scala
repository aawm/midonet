/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.services.vxgw

import java.util.UUID

import scala.collection.JavaConversions._

import com.google.common.base.Preconditions
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.brain.southbound.vtep.VtepConstants
import org.midonet.cluster.DataClient
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.makeRunnable

/** This Observer is able to listen on an Observable that emits MacLocation
  * instances and update a Neutron network's state accordingly. */
class BusObserver(dataClient: DataClient, networkId: UUID,
                  macPortMap: MacPortMap, zkConnWatcher: ZookeeperConnectionWatcher,
                  peerEndpoints: java.util.Map[IPv4Addr, UUID])
    extends Observer[MacLocation] {

    private val log = LoggerFactory.getLogger(vxgwMacSyncingLog(networkId))
    private val lsName = VtepConstants.bridgeIdToLogicalSwitchName(networkId)

    override def onCompleted(): Unit = {
        log.warn(s"Network: $networkId, update bus finished. Unexpected.")
    }
    override def onError(e: Throwable): Unit = {
        log.warn(s"Network: $networkId, error on update bus", e)
    }
    override def onNext(ml: MacLocation): Unit = {
        if (ml == null || !ml.mac.isIEEE802) {
            log.info(s"Network $networkId, ignores malformed MAC $ml")
            return
        }
        if (!ml.logicalSwitchName.equals(lsName)) {
            log.info(s"Network $networkId, ignores unrelated update: $ml")
            return
        }
        if (ml.vxlanTunnelEndpoint == null) {
            val portId = macPortMap.get(ml.mac.IEEE802())
            if (portId != null) {
                applyMacRemoval(ml, portId)
            } // else it's already gone so don't bother
        } else {
            applyMacUpdate(ml)
        }
    }

    private def applyMacRemoval(ml: MacLocation, vxPort: UUID): Unit = {
        val mac = ml.mac.IEEE802()
        try {
            macPortMap.removeIfOwnerAndValue(mac, vxPort)
            dataClient.bridgeGetIp4ByMac(networkId, mac) foreach { ip =>
                dataClient.bridgeDeleteLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    makeRunnable { applyMacRemoval(ml, vxPort) }, e)
            case t: Throwable =>
                log.error(s"Network $networkId, failed to apply removal $ml", t)
        }
    }

    /** Update the Mac Port table of this network, associating the given MAC
      * to the VxlanPort that corresponds to the VxLAN tunnel endpoint
      * contained in the given MacLocation. */
    private def applyMacUpdate(ml: MacLocation): Unit = {
        Preconditions.checkArgument(ml.vxlanTunnelEndpoint != null)
        val newVxPortId = peerEndpoints.get(ml.vxlanTunnelEndpoint)
        if (newVxPortId == null) {
            // The change didn't happen in a VTEP, ignore
            return
        }
        val mac = ml.mac.IEEE802()
        val currPortId = macPortMap.get(mac)
        val isNew = currPortId == null
        val isChanged = isNew || !currPortId.equals(newVxPortId)
        try {
            if (isNew || isChanged) {
                macPortMap.put(mac, newVxPortId)
            }
            if (!isNew && isChanged) {
                // See MN-2637, this removal is exposed to races
                macPortMap.removeIfOwnerAndValue(mac, currPortId)
            }
        } catch {
            case e: StateAccessException =>
                log.warn(s"Network $networkId, failed to apply $ml", e)
                zkConnWatcher.handleError(s"MAC update retry: $networkId",
                                          makeRunnable { applyMacUpdate(ml) },
                                          e)
        }

        // Fill the ARP supresion table
        if (ml.ipAddr != null && newVxPortId != null) {
            learnIpOnMac(mac, ml.ipAddr, newVxPortId)
        }
    }

    /** Reliably associated an IP to a MAC as long as the expected port is
      * associated to the MAC. */
    private def learnIpOnMac(mac: MAC, ip: IPv4Addr, expectPort: UUID): Unit = {
        try {
            if (expectPort != null && expectPort.equals(macPortMap.get(mac))) {
                dataClient.bridgeAddLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
                log.warn(s"Network $networkId, failed to learn $ip on $mac", e)
                zkConnWatcher.handleError(s"MAC update retry: $networkId",
                    makeRunnable { learnIpOnMac(mac, ip, expectPort) }, e
                )
            case e: Throwable =>
                log.error(s"Network $networkId, can't learn $ip on $mac", e)
        }
    }


}

