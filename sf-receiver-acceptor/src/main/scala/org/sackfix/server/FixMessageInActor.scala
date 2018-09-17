package org.sackfix.server

import java.util.Properties

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import org.sackfix.session.SfSessionId
import org.sackfix.boostrap._
import org.sackfix.common.message.SfMessage

import scala.collection.mutable

/** This actor takes incoming messages and passes them into a Kafka stream without responding.
  * It is intended only for forwarding FIX drop copy information to other systems via Kafka.
  */
object FixMessageInActor {
  def props(): Props = Props(new FixMessageInActor)
}

class FixMessageInActor extends Actor with ActorLogging {
  private val SOH_CHAR: Char = 1.toChar
  private val fixTagBlackListProperty: String = "RemoveFixTags"
  private val sentMessages = mutable.HashMap.empty[String, Long]
  private var isOpen = false
  private val kafkaConfig: Properties = {
    val props = new Properties()
    try { // Load the config for Kafka from application.conf.
      val kafkaSettings = context.system.settings.config.getConfig("kafka")
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaSettings.getString("KafkaHost"))
      props.put(ProducerConfig.CLIENT_ID_CONFIG, "TradeReceiver")
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    }
    props
  }
  private val removeFixTags: Set[Int] = context.system.settings.config.getConfig("kafka").getString(fixTagBlackListProperty).split(",").flatMap(line=>line.toStream).map(cell => try {
    Some((cell).toInt)
  } catch { case e: Exception => None }
).filter(split => split==Some).map(tag=>tag.get).toSet
  log.info(s"application.conf $fixTagBlackListProperty is set to remove these tags:'$removeFixTags'")

  override def receive: Receive = {
    case FixSessionOpen(sessionId: SfSessionId, sfSessionActor: ActorRef) =>
      log.info(s"Session ${sessionId.id} is OPEN for business")
      isOpen = true
    case FixSessionClosed(sessionId: SfSessionId) =>
      log.info(s"Session ${sessionId.id} is CLOSED for business")
      isOpen = false
    case BusinessFixMessage(sessionId: SfSessionId, sfSessionActor: ActorRef, message: SfMessage) => sendToKafka(message) //dump into kafka
    case BusinessRejectMessage(sessionId: SfSessionId, sfSessionActor: ActorRef, message: SfMessage) => sendToKafka(message) //dump into kafka
    case BusinessFixMsgOutAck(sessionId: SfSessionId, sfSessionActor: ActorRef, correlationId:String) =>
      // You should have a HashMap of stuff you send, and when you get this remove from your set.
      // Read the Akka IO TCP guide for ACK'ed messages and you will see
      sentMessages.get(correlationId).foreach(tstamp =>
        log.debug(s"$correlationId send duration = ${(System.nanoTime()-tstamp)/1000} Micros"))
  }

  def sendToKafka(message: SfMessage): Unit = {
    val reducedMessage: String = message.fixStr.split(SOH_CHAR).toStream
      .map(f => {
        val arr=f.split('=')
        (arr(0).toInt, arr(1))
      })
      .filter(fixTuple => !removeFixTags.contains(fixTuple._1)).map(fixTup => fixTup._1.toString() + "=" + fixTup._2).toArray[String].mkString(SOH_CHAR.toString)
    val producer: KafkaProducer[String, String] = new KafkaProducer(kafkaConfig)
    val record: ProducerRecord[String, String] = new ProducerRecord("FixEventsIn", reducedMessage)
    producer.send(record, logResult)
  }

  private def logResult(metadata: RecordMetadata, exception: Exception): Unit = {
    if (exception != null) log.error("Error sending data", exception)
    else log.info("Successfully sent data to topic: " + metadata.topic + " and partition: " + metadata.partition + " with offset: " + metadata.offset)
  }

}