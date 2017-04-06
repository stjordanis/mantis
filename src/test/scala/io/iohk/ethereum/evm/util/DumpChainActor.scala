package io.iohk.ethereum.evm.util

import java.io.FileWriter

import akka.actor.{Actor, ActorRef, _}
import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.network.PeerActor.{MessageReceived, SendMessage}
import io.iohk.ethereum.network.PeerManagerActor.{GetPeers, Peer, PeersResponse}
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63._
import io.iohk.ethereum.rlp.{RLPEncoder, RLPImplicitConversions, encode}
import org.spongycastle.util.encoders.Hex
import BlockHeaderImplicits._

import scala.collection.immutable.HashMap

class DumpChainActor(peerManager: ActorRef) extends Actor {
  var stateNodesHashes: Set[ByteString] = Set.empty
  var contractNodesHashes: Set[ByteString] = Set.empty
  var evmCodeHashes: Set[ByteString] = Set.empty

  var blockHeadersStorage: Map[ByteString, BlockHeader] = HashMap.empty
  var blockBodyStorage: Map[ByteString, BlockBody] = HashMap.empty
  var blockReceiptsStorage: Map[ByteString, Seq[Receipt]] = HashMap.empty
  var stateStorage: Map[ByteString, MptNode] = HashMap.empty
  var contractStorage: Map[ByteString, MptNode] = HashMap.empty
  var evmCodeStorage: Map[ByteString, ByteString] = HashMap.empty

  var peers: Seq[Peer] = Nil

  override def preStart(): Unit = {
    peerManager ! GetPeers
  }

  // scalastyle:off
  override def receive: Receive = {
    case PeersResponse(p) =>
      peers = p

    case MessageReceived(m: BlockHeaders) =>
      val headerHashes = m.headers.map(_.hash)
      val mptRoots: Seq[ByteString] = m.headers.map(_.stateRoot)

      m.headers.foreach { h =>
        blockHeadersStorage = blockHeadersStorage + (h.hash -> h)
      }

      peers.headOption.foreach { case Peer(_, actor) =>
        actor ! SendMessage(GetBlockBodies(headerHashes))
        actor ! SendMessage(GetReceipts(headerHashes.drop(1)))
        actor ! SendMessage(GetNodeData(mptRoots))
        stateNodesHashes = stateNodesHashes ++ mptRoots.toSet
      }

    case MessageReceived(m: BlockBodies) =>
      m.bodies.zip(blockHeadersStorage.keys).foreach { case (b, h) =>
        blockBodyStorage = blockBodyStorage + (h -> b)
      }


    case MessageReceived(m: Receipts) =>
      m.receiptsForBlocks.zip(blockHeadersStorage.keys).foreach { case (r, h) =>
        blockReceiptsStorage = blockReceiptsStorage + (h -> r)
      }


    case MessageReceived(m: NodeData) =>
      val emptyStorage = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))
      val emptyEvm = ByteString(Hex.decode("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))

      val stateNodes = m.values.filter(node => stateNodesHashes.contains(kec256(node)))
      val contractNodes = m.values.filter(node => contractNodesHashes.contains(kec256(node)))
      val evmCode = m.values.filter(node => evmCodeHashes.contains(kec256(node)))

      val nodes = NodeData(stateNodes).values.indices.map(i => NodeData(stateNodes).getMptNode(i))

      val children = nodes.flatMap {
        case n: MptBranch => n.children.collect { case Left(h: MptHash) if h.hash.nonEmpty => h.hash }
        case MptExtension(_, Left(h)) => Seq(h.hash)
        case n: MptLeaf => Seq.empty
        case _ => Seq.empty
      }

      var cChildren: Seq[ByteString] = Nil
      var evmTorequest: Seq[ByteString] = Nil

      nodes.foreach {
        case n: MptLeaf =>
          if (n.getAccount.codeHash != emptyEvm) {
            peers.headOption.foreach { case Peer(_, actor) =>
              evmTorequest = evmTorequest :+ n.getAccount.codeHash
              evmCodeHashes = evmCodeHashes + n.getAccount.codeHash
            }
          }
          if (n.getAccount.storageRoot != emptyStorage) {
            peers.headOption.foreach { case Peer(_, actor) =>
              cChildren = cChildren :+ n.getAccount.storageRoot
              contractNodesHashes = contractNodesHashes + n.getAccount.storageRoot
            }
          }
        case _ =>
      }

      val cNodes = NodeData(contractNodes).values.indices.map(i => NodeData(contractNodes).getMptNode(i))
      cChildren = cChildren ++ cNodes.flatMap {
        case n: MptBranch => n.children.collect { case Left(h: MptHash) if h.hash.nonEmpty => h.hash }
        case MptExtension(_, Left(h)) => Seq(h.hash)
        case _ => Seq.empty
      }
      peers.headOption.foreach { case Peer(_, actor) =>
        actor ! SendMessage(GetNodeData(children ++ cChildren ++ evmTorequest))
        stateNodesHashes = stateNodesHashes ++ children.toSet
        contractNodesHashes = contractNodesHashes ++ cChildren.toSet
      }

      evmCode.foreach { e =>
        evmCodeStorage = evmCodeStorage + (kec256(e) -> e)
      }

      nodes.foreach { n =>
        stateStorage = stateStorage + (n.hash -> n)
      }

      cNodes.foreach { n =>
        contractStorage = contractStorage + (n.hash -> n)
      }

      if (children.isEmpty && cChildren.isEmpty && evmTorequest.isEmpty) {
        import RLPImplicitConversions._
        import Receipt._

        val headersFile = new FileWriter("headers.txt", true)
        val bodiesFile = new FileWriter("bodies.txt", true)
        val receiptsFile = new FileWriter("receipts.txt", true)
        val stateTreeFile = new FileWriter("stateTree.txt", true)
        val contractTreesFile = new FileWriter("contractTrees.txt", true)
        val evmCodeFile = new FileWriter("evmCode.txt", true)

        def dumpToFile[T](fw: FileWriter, element: (ByteString, T))(implicit enc: RLPEncoder[T]): Unit = element match {
          case (h, v) => fw.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(encode(v))}\n")
        }

        blockHeadersStorage.foreach(dumpToFile(headersFile, _))
        blockBodyStorage.foreach(dumpToFile(bodiesFile, _))
        blockReceiptsStorage.foreach { case (h, v: Seq[Receipt]) => receiptsFile.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(encode(toRlpList(v)))}\n") }
        stateStorage.foreach(dumpToFile(stateTreeFile, _))
        contractStorage.foreach(dumpToFile(contractTreesFile, _))
        evmCodeStorage.foreach { case (h, v) => evmCodeFile.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(v.toArray[Byte])}\n") }

        headersFile.close()
        bodiesFile.close()
        receiptsFile.close()
        stateTreeFile.close()
        contractTreesFile.close()
        evmCodeFile.close()
        println("chain dumped to file")
      }
  }
}
