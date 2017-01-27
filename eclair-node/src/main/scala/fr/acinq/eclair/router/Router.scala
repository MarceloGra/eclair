package fr.acinq.eclair.router

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, LexicographicalOrdering}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL, Register}
import fr.acinq.eclair.wire._
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 24/05/2016.
  */

class Router(watcher: ActorRef, announcement: NodeAnnouncement) extends Actor with ActorLogging {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])
  context.system.scheduler.schedule(10 seconds, 60 seconds, self, 'tick_broadcast)

  def receive: Receive = main(myself = announcement, nodes = Map(announcement.nodeId -> announcement), channels = Map(), updates = Map(), rebroadcast = Nil)

  def main(
            myself: NodeAnnouncement,
            nodes: Map[BinaryData, NodeAnnouncement],
            channels: Map[Long, ChannelAnnouncement],
            updates: Map[(Long, BinaryData), ChannelUpdate],
            rebroadcast: Seq[RoutingMessage]): Receive = {

    case ChannelChangedState(channel, transport, remoteNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val (c, u) = if (LexicographicalOrdering.isLessThan(myself.nodeId, remoteNodeId)) {
        (
          makeChannelAnnouncement(d.commitments.channelId, myself.nodeId, remoteNodeId, d.params.localParams.fundingPrivKey.publicKey.toBin, d.params.remoteParams.fundingPubKey.toBin),
          makeChannelUpdate(Globals.Node.privateKey, d.commitments.channelId, true, Platform.currentTime / 1000)
        )
      } else {
        (
          makeChannelAnnouncement(d.commitments.channelId, remoteNodeId, myself.nodeId, d.params.remoteParams.fundingPubKey.toBin, d.params.localParams.fundingPrivKey.publicKey.toBin),
          makeChannelUpdate(Globals.Node.privateKey, d.commitments.channelId, false, Platform.currentTime / 1000)
        )
      }
      // we send all known announcements to the new peer
      channels.values.foreach(transport ! _)
      nodes.values.foreach(transport ! _)
      updates.values.foreach(transport ! _)
      // and we queue the new announcements for everybody
      log.debug(s"queueing channel announcement $c")
      log.debug(s"queueing node announcement $myself")
      // let's trigger the broadcast immediately so that we don't wait for 60 seconds to announce our newly created channel
      self ! 'tick_broadcast
      context become main(myself, nodes, channels + (c.channelId -> c), updates, rebroadcast :+ c :+ myself :+ u)

    case s: ChannelChangedState =>
      // other channel changed state messages are ignored

    case c: ChannelAnnouncement if channels.containsKey(c.channelId) =>
      log.debug(s"ignoring $c (duplicate)")

    case c: ChannelAnnouncement =>
      // TODO: check channel output = P2WSH(nodeid1, nodeid2)
      // TODO: check sigs
      // TODO: blacklist if already received same channel id and different node ids
      // TODO: check feature bit set
      // TODO: forget channel once funding tx spent (add watch)
      //watcher ! WatchSpent(self, txId: BinaryData, outputIndex: Int, minDepth: Int, event: BitcoinEvent)
      log.info(s"added channel channelId=${c.channelId} (nodes=${nodes.size} channels=${channels.size + 1})")
      context become main(myself, nodes, channels + (c.channelId -> c), updates, rebroadcast :+ c)

    case n: NodeAnnouncement if !checkSig(n) =>
    // TODO: fail connection (should probably be done in the auth handler or channel)

    case n: NodeAnnouncement if !channels.values.exists(c => c.nodeId1 == n.nodeId || c.nodeId2 == n.nodeId) =>
      log.debug(s"ignoring $n (no related channel found)")

    case n: NodeAnnouncement if nodes.containsKey(n.nodeId) && nodes(n.nodeId).timestamp >= n.timestamp =>
      log.debug(s"ignoring announcement $n (old timestamp or duplicate)")

    case n: NodeAnnouncement =>
      log.info(s"added/replaced node nodeId=${n.nodeId} (nodes=${nodes.size + 1} channels=${channels.size})")
      context become main(myself, nodes + (n.nodeId -> n), channels, updates, rebroadcast :+ n)

    case u: ChannelUpdate if !channels.contains(u.channelId) =>
      log.debug(s"ignoring $u (no related channel found)")

    case u: ChannelUpdate if !checkSig(u, getNodeId(u, channels(u.channelId))) =>
    // TODO: fail connection (should probably be done in the auth handler or channel)

    case u: ChannelUpdate =>
      val channel = channels(u.channelId)
      val nodeId = getNodeId(u, channel)
      if (updates.contains((u.channelId, nodeId)) && updates((u.channelId, nodeId)).timestamp >= u.timestamp) {
        log.debug(s"ignoring $u (old timestamp or duplicate)")
      } else {
        context become main(myself, nodes, channels, updates + ((u.channelId, nodeId) -> u), rebroadcast :+ u)
      }

    case 'tick_broadcast if rebroadcast.size ==0 =>
      // no-op

    case 'tick_broadcast =>
      log.info(s"broadcasting ${rebroadcast.size} routing messages")
      rebroadcast.foreach(context.actorSelection(Register.actorPathToTransportHandlers) ! _)
      context become main(myself, nodes, channels, updates, Nil)

    case 'network => sender ! channels.values

    case other => log.warning(s"unhandled message $other")

    //case RouteRequest(start, end) => findRoute(start, end, channels) map (RouteResponse(_)) pipeTo sender
  }
}

object Router {

  def props(watcher: ActorRef, announcement: NodeAnnouncement) = Props(classOf[Router], watcher, announcement)

  // TODO: placeholder for signatures, we don't actually sign for now
  val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  def makeChannelAnnouncement(channelId: Long, nodeId1: BinaryData, nodeId2: BinaryData, fundingKey1: BinaryData, fundingKey2: BinaryData): ChannelAnnouncement = {
    val unsigned = ChannelAnnouncement(
      nodeSignature1 = DUMMY_SIG,
      nodeSignature2 = DUMMY_SIG,
      channelId = channelId,
      bitcoinSignature1 = DUMMY_SIG,
      bitcoinSignature2 = DUMMY_SIG,
      nodeId1 = nodeId1,
      nodeId2 = nodeId2,
      bitcoinKey1 = fundingKey1,
      bitcoinKey2 = fundingKey2
    )
    unsigned
  }

  def makeNodeAnnouncement(secret: PrivateKey, alias: String, color: (Byte, Byte, Byte), addresses: List[InetSocketAddress], timestamp: Long): NodeAnnouncement = {
    require(alias.size <= 32)
    val unsigned = NodeAnnouncement(
      signature = DUMMY_SIG,
      timestamp = timestamp,
      nodeId = secret.publicKey.toBin,
      rgbColor = color,
      alias = alias,
      features = "",
      addresses = addresses
    )
    unsigned
    /*val bin = Codecs.nodeAnnouncementCodec.encode(unsigned).toOption.map(_.toByteArray).getOrElse(throw new RuntimeException(s"cannot encode $unsigned"))
    val hash = sha256(sha256(bin.drop(64)))
    val sig = encodeSignature(sign(hash, secret))
    unsigned.copy(signature = sig)*/
  }

  def makeChannelUpdate(secret: PrivateKey, channelId: Long, isNodeId1: Boolean, timestamp: Long): ChannelUpdate = {
    val unsigned = ChannelUpdate(
      signature = DUMMY_SIG,
      channelId = channelId,
      timestamp = timestamp,
      flags = if (isNodeId1) "0000" else "0001",
      cltvExpiryDelta = Globals.expiry_delta_blocks,
      htlcMinimumMsat = Globals.htlc_minimum_msat,
      feeBaseMsat = Globals.fee_base_msat,
      feeProportionalMillionths = Globals.fee_proportional_msat
    )
    unsigned
    /*val bin = Codecs.channelUpdateCodec.encode(unsigned).toOption.map(_.toByteArray).getOrElse(throw new RuntimeException(s"cannot encode $unsigned"))
    val hash = sha256(sha256(bin.drop(64)))
    val sig = encodeSignature(sign(hash, secret))
    unsigned.copy(signature = sig)*/
  }

  def checkSig(ann: NodeAnnouncement): Boolean = true /*{
    val bin = Codecs.nodeAnnouncementCodec.encode(ann).toOption.map(_.toByteArray).getOrElse(throw new RuntimeException(s"cannot encode $ann"))
    val hash = sha256(sha256(bin.drop(64)))
    verifySignature(hash, ann.signature, PublicKey(ann.nodeId))
  }*/

  def checkSig(ann: ChannelUpdate, nodeId: BinaryData): Boolean = true /*{
    val bin = Codecs.channelUpdateCodec.encode(ann).toOption.map(_.toByteArray).getOrElse(throw new RuntimeException(s"cannot encode $ann"))
    val hash = sha256(sha256(bin.drop(64)))
    verifySignature(hash, ann.signature, PublicKey(nodeId))
  }*/

  def getNodeId(u: ChannelUpdate, channel: ChannelAnnouncement): BinaryData = {
    require(u.flags.data.size == 2, s"invalid flags length ${u.flags.data.size} != 2")
    // the least significant bit tells us if it is node1 or node2
    if (u.flags.data(1) % 2 == 0) channel.nodeId1 else channel.nodeId2
  }

  def findRouteDijkstra(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, ChannelDesc]): Seq[BinaryData] = {
    class NamedEdge(val id: BinaryData) extends DefaultEdge
    val g = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    channels.values.foreach(x => {
      g.addVertex(x.a)
      g.addVertex(x.b)
      g.addEdge(x.a, x.b, new NamedEdge(x.id))
    })
    Option(new DijkstraShortestPath(g, myNodeId, targetNodeId).getPath) match {
      case Some(path) => {
        val vertices = path.getEdgeList.foldLeft(List(path.getStartVertex)) {
          case (rest :+ v, edge) if g.getEdgeSource(edge) == v => rest :+ v :+ g.getEdgeTarget(edge)
          case (rest :+ v, edge) if g.getEdgeTarget(edge) == v => rest :+ v :+ g.getEdgeSource(edge)
        }
        vertices
      }
      case None => throw new RuntimeException("route not found")
    }
  }

  def findRoute(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, ChannelDesc])(implicit ec: ExecutionContext): Future[Seq[BinaryData]] = Future {
    findRouteDijkstra(myNodeId, targetNodeId, channels)
  }
}

case class ChannelDesc(id: BinaryData, a: BinaryData, b: BinaryData)

case class RouteRequest(source: BinaryData, target: BinaryData)

case class RouteResponse(route: Seq[BinaryData])