// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import akka.actor._
import akka.event.LoggingReceive
import collection.mutable
import scala.collection.JavaConverters._
import collection.mutable.{HashMap, MultiMap}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.Nullable
import javax.inject.Inject

import org.midonet.cache.Cache
import org.midonet.midolman.PacketWorkflowActor.Start
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.cluster.DataClient
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.odp.flows.FlowAction
import org.midonet.packets.Ethernet


object DeduplicationActor extends Referenceable {
    override val Name = "DeduplicationActor"

    // Messages
    case class HandlePacket(packet: Packet)

    case class ApplyFlow(actions: Seq[FlowAction[_]], cookie: Option[Int])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

}


class DeduplicationActor extends Actor with ActorLogWithoutPath with
        UserspaceFlowActionTranslator {

    import DeduplicationActor._

    @Inject var datapathConnection: OvsDatapathConnection = null
    @Inject var clusterDataClient: DataClient = null
    @Inject @Nullable var connectionCache: Cache = null

    var datapath: Datapath = null
    var dpState: DatapathState = null
    val idGenerator: AtomicInteger = new AtomicInteger(0)

    // data structures to handle the duplicate packets.
    private val dpMatchToCookie = HashMap[FlowMatch, Int]()
    private val cookieToPendedPackets: MultiMap[Int, Packet] =
        new HashMap[Int, mutable.Set[Packet]] with MultiMap[Int, Packet]


    override def preStart() {
        super.preStart()
    }

    def receive = LoggingReceive {
        case DatapathController.DatapathReady(dp, state) =>
            if (null == datapath) {
                datapath = dp
                dpState = state
                installPacketInHook()
                log.info("Datapath hook installed")
            }

        case HandlePacket(packet) =>
            dpMatchToCookie.get(packet.getMatch) match {
            case None =>
                 val cookie:Int = idGenerator.getAndIncrement
                 cookieToPendedPackets.addBinding(cookie, packet)
                 // if there is nothing here create the actor that will handle its flow,
                 val packetActor =
                    context.system.actorOf(Props(
                        new PacketWorkflowActor(datapathConnection, dpState,
                            datapath, clusterDataClient, connectionCache, packet,
                            Left(cookie))),
                        name = "PacketWorkflowActor-"+cookie)

                 log.debug("Created new {} actor.", "PacketWorkflowActor-" + cookie)
                 packetActor ! Start()

            case Some(cookie: Int) =>
                log.debug("A matching packet with cookie {} is already being handled ", cookie)
                // Simulation in progress. Just pend the packet.
                cookieToPendedPackets.addBinding(cookie, packet)
            }

        case ApplyFlow(actions, cookieOpt) => cookieOpt foreach { cookie =>
            cookieToPendedPackets.remove(cookie) foreach { pendedPackets =>
                val packet = pendedPackets.last
                dpMatchToCookie.remove(packet.getMatch)

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (actions.length > 0) {
                    for (unpendedPacket <- pendedPackets.tail) {
                        executePacket(cookie, packet, actions)
                    }
                }
            }

            if (actions.length == 0) {
                context.system.eventStream.publish(DiscardPacket(cookie))
            }
        }

        // This creates a new PacketWorkflowActor and
        // and executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            val packet = new Packet().setData(ethernet.serialize())
            val packetId = scala.util.Random.nextLong()
            val packetActor =
            context.system.actorOf(Props(
                new PacketWorkflowActor(datapathConnection, dpState,
                    datapath, clusterDataClient, connectionCache, packet,
                    Right(egressPort))),
                name = "PacketWorkflowActor-generated-"+ packetId)

            log.debug("Created new {} actor.", "PacketWorkflowActor-generated-"+packetId );
            packetActor ! Start()
    }

    private def executePacket(cookie: Int,
                              packet: Packet,
                              actions: Seq[FlowAction[_]]) {
        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly)
            applyActionsAfterUserspaceMatch(packet)

        if (packet.getActions.size > 0) {
            log.debug("Sending pended packet {} for cookie {}",
                packet, cookie)

            datapathConnection.packetsExecute(datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {}

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} due to {}",
                            packet,
                            if (timeout) "timeout" else "error")
                    }
                })
        }
    }

    private def installPacketInHook() = {
         log.info("Installing packet in handler in the DDA")
         datapathConnection.datapathsSetNotificationHandler(datapath,
                 new Callback[Packet] {
                     def onSuccess(data: Packet) {
                         self ! HandlePacket(data)
                     }

                     def onTimeout() {}

                     def onError(e: NetlinkException) {}
         }).get()
    }

    private def freePendedPackets(cookieOpt: Option[Int]): Unit = {
        cookieOpt match {
            case None => // no pended packets
            case Some(cookie) =>
                val pended = cookieToPendedPackets.remove(cookie)
                val packet = pended.head.last
                dpMatchToCookie.remove(packet.getMatch)
        }
    }
}