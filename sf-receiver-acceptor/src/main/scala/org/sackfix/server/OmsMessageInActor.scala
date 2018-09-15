package org.sackfix.server

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.util.Properties
import java.util.concurrent.TimeUnit

import org.sackfix.boostrap._
import org.sackfix.common.message.SfMessage
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala._
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}
import org.sackfix.session.SfSessionId

import scala.collection.mutable
import scala.io.Source

/** You must implement an actor for business messages.
  * You should inject it into the SfInitiatorActor or SfAcceptorActor depending on
  * if you are a server or a client
  *
  * Backpressure is not implemented in SackFix for IO Buffer filling up on read or write.  If you want to
  * add it please feel free.  Note that you should probably NOT send out orders if you have ACKs outstanding.
  * This will pretty much avoid all back pressure issues. ie if sendMessages.size>1 wait
  */
object OMSMessageInActor {
  def props(): Props = Props(new OMSMessageInActor)
}

class OMSMessageInActor extends Actor with ActorLogging {
  private val sentMessages = mutable.HashMap.empty[String, Long]
  private var isOpen = false
  private val fixTagFilterFile = "example.fix.log"
  private val removeFixTags: Set[Int] = Source.fromFile(fixTagFilterFile).getLines().map(line=>line.split(",")).flatMap(line=>line.toStream).map(cell => try {
    Some((cell.trim).toInt)
  } catch { case e: Exception => None }
).filter(split => split==Some).map(tag=>tag.get).toSet

  override def receive: Receive = {
    case FixSessionOpen(sessionId: SfSessionId, sfSessionActor: ActorRef) =>
      log.info(s"Session ${sessionId.id} is OPEN for business")
      isOpen = true
    case FixSessionClosed(sessionId: SfSessionId) =>
      log.info(s"Session ${sessionId.id} is CLOSED for business")
      isOpen = false
    case BusinessFixMessage(sessionId: SfSessionId, sfSessionActor: ActorRef, message: SfMessage) => {} //dump into kafka
    case BusinessRejectMessage(sessionId: SfSessionId, sfSessionActor: ActorRef, message: SfMessage) => {} //dump into kafka
    case BusinessFixMsgOutAck(sessionId: SfSessionId, sfSessionActor: ActorRef, correlationId:String) =>
      // You should have a HashMap of stuff you send, and when you get this remove from your set.
      // Read the Akka IO TCP guide for ACK'ed messages and you will see
      sentMessages.get(correlationId).foreach(tstamp =>
        log.debug(s"$correlationId send duration = ${(System.nanoTime()-tstamp)/1000} Micros"))
  }

  def sendToKafka(message: SfMessage): Unit = {
    message.fixExtensions.filter(fixTuple => !removeFixTags.contains(fixTuple._1))
  }
}