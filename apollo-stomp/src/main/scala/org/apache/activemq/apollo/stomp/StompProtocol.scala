/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.stomp

import _root_.org.fusesource.hawtdispatch.{DispatchQueue, BaseRetained}
import _root_.org.fusesource.hawtbuf._
import collection.mutable.{ListBuffer, HashMap}
import org.fusesource.hawtdispatch._

import AsciiBuffer._
import org.apache.activemq.apollo.broker._
import java.lang.String
import protocol.{HeartBeatMonitor, ProtocolFactory, Protocol, ProtocolHandler}
import security.SecurityContext
import Stomp._
import BufferConversions._
import java.io.IOException
import org.apache.activemq.apollo.selector.SelectorParser
import org.apache.activemq.apollo.filter.{BooleanExpression, FilterException}
import org.apache.activemq.apollo.transport._
import org.apache.activemq.apollo.store._
import org.apache.activemq.apollo.util._
import java.util.concurrent.TimeUnit
import java.util.Map.Entry
import org.apache.activemq.apollo.dto.{StompConnectionStatusDTO, BindingDTO, DurableSubscriptionBindingDTO, PointToPointBindingDTO}
import scala.util.continuations._

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
/**
 * Creates StompCodec objects that encode/decode the
 * <a href="http://activemq.apache.org/stomp/">Stomp</a> protocol.
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class StompProtocolCodecFactory extends ProtocolCodecFactory.Provider {

  def protocol = PROTOCOL

  def createProtocolCodec() = new StompCodec();

  def isIdentifiable() = true

  def maxIdentificaionLength() = CONNECT.length;

  def matchesIdentification(header: Buffer):Boolean = {
    if (header.length < CONNECT.length) {
      false
    } else {
      header.startsWith(CONNECT) || header.startsWith(STOMP)
    }
  }
}

class StompProtocolFactory extends ProtocolFactory.Provider {

  def create() = StompProtocol

  def create(config: String) = if(config == "stomp") {
    StompProtocol
  } else {
    null
  }

}

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
object StompProtocol extends StompProtocolCodecFactory with Protocol {

  def createProtocolHandler = new StompProtocolHandler

  def encode(message: Message):MessageRecord = {
    StompCodec.encode(message.asInstanceOf[StompFrameMessage])
  }

  def decode(message: MessageRecord) = {
    StompCodec.decode(message)
  }

}


object StompProtocolHandler extends Log {

  // How long we hold a failed connection open so that the remote end
  // can get the resulting error message.
  val DEFAULT_DIE_DELAY = 5*1000L
  var die_delay = DEFAULT_DIE_DELAY

    // How often we can send heartbeats of the connection is idle.
  val DEFAULT_OUTBOUND_HEARTBEAT = 100L
  var outbound_heartbeat = DEFAULT_OUTBOUND_HEARTBEAT

  // How often we want to get heartbeats from the peer if the connection is idle.
  val DEFAULT_INBOUND_HEARTBEAT = 10*1000L
  var inbound_heartbeat = DEFAULT_INBOUND_HEARTBEAT

}

import StompProtocolHandler._


/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class StompProtocolHandler extends ProtocolHandler with DispatchLogging {

  def protocol = "stomp"

  override protected def log = StompProtocolHandler

  protected def dispatchQueue:DispatchQueue = connection.dispatchQueue

  trait AckHandler {
    def track(delivery:Delivery):Unit
    def perform_ack(msgid: AsciiBuffer, uow:StoreUOW=null):Unit
  }

  class AutoAckHandler extends AckHandler {
    def track(delivery:Delivery) = {
      if( delivery.ack!=null ) {
        delivery.ack(null)
      }
    }
    
    def perform_ack(msgid: AsciiBuffer, uow:StoreUOW=null) = {
      async_die("The subscription ack mode does not expect ACK frames")
    }
  }

  class SessionAckHandler extends AckHandler{
    var consumer_acks = ListBuffer[(AsciiBuffer, (StoreUOW)=>Unit)]()

    def track(delivery:Delivery) = {
      queue.apply {
        if( protocol_version eq V1_0 ) {
          // register on the connection since 1.0 acks may not include the subscription id
          connection_ack_handlers += ( delivery.message.id-> this )
        }
        consumer_acks += (( delivery.message.id, delivery.ack ))
      }

    }


    def perform_ack(msgid: AsciiBuffer, uow:StoreUOW=null) = {

      // session acks ack all previously recieved messages..
      var found = false
      val (acked, not_acked) = consumer_acks.partition{ case (id, ack)=>
        if( found ) {
          false
        } else {
          if( id == msgid ) {
            found = true
          }
          true
        }
      }

      if( acked.isEmpty ) {
        async_die("ACK failed, invalid message id: %s".format(msgid))
      } else {
        consumer_acks = not_acked
        acked.foreach{case (id, ack)=>
          if( ack!=null ) {
            ack(uow)
          }
        }
      }

      if( protocol_version eq V1_0 ) {
        connection_ack_handlers.remove(msgid)
      }
    }



  }
  class MessageAckHandler extends AckHandler {
    var consumer_acks = HashMap[AsciiBuffer, (StoreUOW)=>Unit]()

    def track(delivery:Delivery) = {
      queue.apply {
        if( protocol_version eq V1_0 ) {
          // register on the connection since 1.0 acks may not include the subscription id
          connection_ack_handlers += ( delivery.message.id-> this )
        }
        consumer_acks += ( delivery.message.id -> delivery.ack )
      }
    }

    def perform_ack(msgid: AsciiBuffer, uow:StoreUOW=null) = {
      consumer_acks.remove(msgid) match {
        case Some(ack) =>
          if( ack!=null ) {
            ack(uow)
          }
        case None => async_die("ACK failed, invalid message id: %s".format(msgid))
      }

      if( protocol_version eq V1_0 ) {
        connection_ack_handlers.remove(msgid)
      }
    }
  }

  class StompConsumer(val subscription_id:Option[AsciiBuffer], val destination:Destination, val ack_handler:AckHandler, val selector:(AsciiBuffer, BooleanExpression), val binding:BindingDTO) extends BaseRetained with DeliveryConsumer {
    val dispatchQueue = StompProtocolHandler.this.dispatchQueue


    dispatchQueue.retain
    setDisposer(^{
      session_manager.release
      dispatchQueue.release
    })

    override def connection = Some(StompProtocolHandler.this.connection)

    def is_persistent = false

    def matches(delivery:Delivery) = {
      if( delivery.message.protocol eq StompProtocol ) {
        if( selector!=null ) {
          selector._2.matches(delivery.message)
        } else {
          true
        }
      } else {
        false
      }
    }

    def connect(p:DeliveryProducer) = new DeliverySession {
      retain

      def producer = p
      def consumer = StompConsumer.this

      val session = session_manager.open(producer.dispatchQueue)

      def close = {
        session_manager.close(session)
        release
      }

      // Delegate all the flow control stuff to the session
      def full = session.full
      def offer(delivery:Delivery) = {
        if( session.full ) {
          false
        } else {
          ack_handler.track(delivery)
          var frame = delivery.message.asInstanceOf[StompFrameMessage].frame
          if( subscription_id != None ) {
            frame = frame.append_headers((SUBSCRIPTION, subscription_id.get)::Nil)
          }
          frame.retain
          val rc = session.offer(frame)
          assert(rc, "offer should be accepted since it was not full")
          true
        }
      }

      def refiller = session.refiller
      def refiller_=(value:Runnable) = { session.refiller=value }

    }
  }

  var session_manager:SinkMux[StompFrame] = null
  var connection_sink:Sink[StompFrame] = null

  var closed = false
  var consumers = Map[AsciiBuffer, StompConsumer]()

  var producerRoutes = new LRUCache[Destination, DeliveryProducerRoute](10) {
    override def onCacheEviction(eldest: Entry[Destination, DeliveryProducerRoute]) = {
      host.router.disconnect(eldest.getValue)
    }
  }

  var host:VirtualHost = null

  private def queue = connection.dispatchQueue

  // uses by STOMP 1.0 clients
  var connection_ack_handlers = HashMap[AsciiBuffer, AckHandler]()

  var session_id:AsciiBuffer = _
  var protocol_version:AsciiBuffer = _

  var heart_beat_monitor:HeartBeatMonitor = new HeartBeatMonitor
  val security_context = new SecurityContext
  var waiting_on:String = "client request"


  override def create_connection_status = {
    var rc = new StompConnectionStatusDTO
    rc.protocol_version = if( protocol_version == null ) null else protocol_version.toString
    rc.user = security_context.user
    rc.subscription_count = consumers.size
    rc.waiting_on = waiting_on
    rc
  }

  class ProtocolException(msg:String) extends RuntimeException(msg)
  class Break extends RuntimeException

  private def async_die(msg:String, e:Throwable=null) = try {
    die(msg)
  } catch {
    case x:Break=>
  }

  private def die[T](msg:String, e:Throwable=null):T = {
    if( e!=null) {
      debug(e, "Shutting connection down due to: "+msg)
    } else {
      debug("Shutting connection down due to: "+msg)
    }
    die((MESSAGE_HEADER, ascii(msg))::Nil, "")
  }

  private def die[T](headers:HeaderMap, body:String):T = {
    if( !connection.stopped ) {
      suspendRead("shutdown")
      connection.transport.offer(StompFrame(ERROR, headers, BufferContent(ascii(body))) )
      // TODO: if there are too many open connections we should just close the connection
      // without waiting for the error to get sent to the client.
      queue.after(die_delay, TimeUnit.MILLISECONDS) {
        connection.stop()
      }
    }
    throw new Break()
  }

  override def onTransportConnected() = {

    session_manager = new SinkMux[StompFrame]( MapSink(connection.transportSink){x=>
      trace("sending frame: %s", x)
      x
    }, dispatchQueue, StompFrame)
    connection_sink = new OverflowSink(session_manager.open(dispatchQueue));
    connection_sink.refiller = NOOP
    resumeRead
  }

  override def onTransportDisconnected() = {
    if( !closed ) {
      heart_beat_monitor.stop
      closed=true;

      import collection.JavaConversions._
      producerRoutes.foreach{
        case(_,route)=> host.router.disconnect(route)
      }
      producerRoutes.clear
      consumers.foreach {
        case (_,consumer)=>
          if( consumer.binding==null ) {
            host.router.unbind(consumer.destination, consumer)
          } else {
            reset {
              val queue = host.router.get_queue(consumer.binding)
              queue.foreach( _.unbind(consumer::Nil) )
            }
          }
      }
      consumers = Map()
      trace("stomp protocol resources released")
    }
  }


  override def onTransportCommand(command:Any) = {
    try {
      command match {
        case s:StompCodec =>
          // this is passed on to us by the protocol discriminator
          // so we know which wire format is being used.
        case frame:StompFrame=>

          trace("received frame: %s", frame)

          if( protocol_version == null ) {

            frame.action match {
              case STOMP =>
                on_stomp_connect(frame.headers)
              case CONNECT =>
                on_stomp_connect(frame.headers)
              case DISCONNECT =>
                connection.stop
              case _ =>
                die("Client must first send a connect frame");
            }

          } else {
            frame.action match {
              case SEND =>
                on_stomp_send(frame)
              case ACK =>
                on_stomp_ack(frame)

              case BEGIN =>
                on_stomp_begin(frame.headers)
              case COMMIT =>
                on_stomp_commit(frame.headers)
              case ABORT =>
                on_stomp_abort(frame.headers)
              case SUBSCRIBE =>
                on_stomp_subscribe(frame.headers)
              case UNSUBSCRIBE =>
                on_stomp_unsubscribe(frame.headers)

              case DISCONNECT =>
                connection.stop

              case _ =>
                die("Invalid frame: "+frame.action);
            }
          }

        case _=>
          warn("Internal Server Error: unexpected command type")
          die("Internal Server Error");
      }
    }  catch {
      case e: Break =>
      case e:Exception =>
        async_die("Internal Server Error", e);
    }
  }


  def suspendRead(reason:String) = {
    waiting_on = reason
    connection.transport.suspendRead
  }
  def resumeRead() = {
    waiting_on = "client request"
    connection.transport.resumeRead
  }

  def on_stomp_connect(headers:HeaderMap):Unit = {

    security_context.user = get(headers, LOGIN).toString
    security_context.password = get(headers, PASSCODE).toString

    val accept_versions = get(headers, ACCEPT_VERSION).getOrElse(V1_0).split(COMMA).map(_.ascii)
    protocol_version = SUPPORTED_PROTOCOL_VERSIONS.find( v=> accept_versions.contains(v) ) match {
      case Some(x) => x
      case None=>
        val supported_versions = SUPPORTED_PROTOCOL_VERSIONS.mkString(",")
        die((MESSAGE_HEADER, ascii("version not supported"))::
            (VERSION, ascii(supported_versions))::Nil,
            "Supported protocol versions are %s".format(supported_versions))
    }

    val heart_beat = get(headers, HEART_BEAT).getOrElse(DEFAULT_HEAT_BEAT)
    heart_beat.split(COMMA).map(_.ascii) match {
      case Array(cx,cy) =>
        try {
          val can_send = cx.toString.toLong
          val please_send = cy.toString.toLong

          if( inbound_heartbeat>=0 && can_send > 0 ) {
            heart_beat_monitor.read_interval = inbound_heartbeat.max(can_send)

            // lets be a little forgiving to account to packet transmission latency.
            heart_beat_monitor.read_interval += heart_beat_monitor.read_interval.min(5000)

            heart_beat_monitor.on_dead = () => {
              async_die("Stale connection.  Missed heartbeat.")
            }
          }
          if( outbound_heartbeat>=0 && please_send > 0 ) {
            heart_beat_monitor.write_interval = outbound_heartbeat.max(please_send)
            heart_beat_monitor.on_keep_alive = () => {
              connection.transport.offer(NEWLINE_BUFFER)
            }
          }

          heart_beat_monitor.transport = connection.transport
          heart_beat_monitor.start

        } catch {
          case x:NumberFormatException=>
            die("Invalid heart-beat header: "+heart_beat)
        }
      case _ =>
        die("Invalid heart-beat header: "+heart_beat)
    }

    def noop = shift {  k: (Unit=>Unit) => k() }
    reset {
      suspendRead("virtual host lookup")
      val host_header = get(headers, HOST)
      val host = host_header match {
        case None=>
          connection.connector.broker.getDefaultVirtualHost
        case Some(host)=>
          connection.connector.broker.getVirtualHost(host)
      }
      resumeRead
      if(host==null) {
        async_die("Invalid virtual host: "+host_header.get)
        noop // to make the cps compiler plugin happy.
      } else {
        this.host=host

        var authenticated = true;

        if( host.authenticator!=null ) {
          suspendRead("authenticating")
          authenticated = host.authenticator.authenticate(security_context)
          resumeRead
        } else {
          noop // to make the cps compiler plugin happy.
        }

        if( !authenticated ) {
            async_die("Authentication failed.")
        } else {
          val outbound_heart_beat_header = ascii("%d,%d".format(outbound_heartbeat,inbound_heartbeat))
          session_id = ascii(this.host.config.id + ":"+this.host.session_counter.incrementAndGet)

          connection_sink.offer(
            StompFrame(CONNECTED, List(
              (VERSION, protocol_version),
              (SESSION, session_id),
              (HEART_BEAT, outbound_heart_beat_header)
            )))

          if( this.host.direct_buffer_pool!=null ) {
            val wf = connection.transport.getProtocolCodec.asInstanceOf[StompCodec]
            wf.memory_pool = this.host.direct_buffer_pool
          }
        }

      }
    }

  }

  def get(headers:HeaderMap, names:List[AsciiBuffer]):List[Option[AsciiBuffer]] = {
    names.map(x=>get(headers, x))
  }

  def get(headers:HeaderMap, name:AsciiBuffer):Option[AsciiBuffer] = {
    val i = headers.iterator
    while( i.hasNext ) {
      val entry = i.next
      if( entry._1 == name ) {
        return Some(entry._2)
      }
    }
    None
  }

  def on_stomp_send(frame:StompFrame) = {

    get(frame.headers, DESTINATION) match {
      case None=>
        frame.release
        die("destination not set.")

      case Some(dest)=>

        get(frame.headers, TRANSACTION) match {
          case None=>
            perform_send(frame)
          case Some(txid)=>
            get_or_create_tx_queue(txid).add { uow=>
              perform_send(frame, uow)
            }
        }

    }
  }

  def perform_send(frame:StompFrame, uow:StoreUOW=null): Unit = {

    val destiantion: Destination = get(frame.headers, DESTINATION).get
    producerRoutes.get(destiantion) match {
      case null =>
        // create the producer route...

        val producer = new DeliveryProducer() {
          override def connection = Some(StompProtocolHandler.this.connection)

          override def dispatchQueue = queue
        }

        // don't process frames until producer is connected...
        connection.transport.suspendRead
        host.router.connect(destiantion, producer) {
          route =>
            if (!connection.stopped) {
              resumeRead
              route.refiller = ^ {
                resumeRead
              }
              producerRoutes.put(destiantion, route)
              send_via_route(route, frame, uow)
            }
        }

      case route =>
        // we can re-use the existing producer route
        send_via_route(route, frame, uow)

    }
  }


  var message_id_counter = 0;
  def next_message_id = {
    message_id_counter += 1
    // TODO: properly generate mesage ids
    new AsciiBuffer("msg:"+message_id_counter);
  }

  def send_via_route(route:DeliveryProducerRoute, frame:StompFrame, uow:StoreUOW) = {
    var storeBatch:StoreUOW=null
    // User might be asking for ack that we have processed the message..
    val receipt = frame.header(RECEIPT_REQUESTED)

    if( !route.targets.isEmpty ) {

      // We may need to add some headers..
      var message = get( frame.headers, MESSAGE_ID) match {
        case None=>
          var updated_headers:HeaderMap=Nil;
          updated_headers ::= (MESSAGE_ID, next_message_id)
          StompFrameMessage(StompFrame(MESSAGE, frame.headers, frame.content, updated_headers))
        case Some(id)=>
          StompFrameMessage(StompFrame(MESSAGE, frame.headers, frame.content))
      }

      val delivery = new Delivery
      delivery.message = message
      delivery.size = message.frame.size
      delivery.uow = uow

      if( receipt!=null ) {
        delivery.ack = { storeTx =>
          dispatchQueue <<| ^{
            connection_sink.offer(StompFrame(RECEIPT, List((RECEIPT_ID, receipt))))
          }
        }
      }

      // routes can always accept at least 1 delivery...
      assert( !route.full )
      route.offer(delivery)
      if( route.full ) {
        // but once it gets full.. suspend, so that we get more stomp messages
        // until it's not full anymore.
        suspendRead("blocked destination: "+route.destination)
      }

    } else {
      // info("Dropping message.  No consumers interested in message.")
      if( receipt!=null ) {
        connection_sink.offer(StompFrame(RECEIPT, List((RECEIPT_ID, receipt))))
      }
    }
    frame.release
  }

  def on_stomp_subscribe(headers:HeaderMap):Unit = {
    val dest = get(headers, DESTINATION).getOrElse(die("destination not set."))
    val destination:Destination = dest

    val subscription_id = get(headers, ID)
    var id:AsciiBuffer = subscription_id.getOrElse {
      if( protocol_version eq V1_0 ) {
          // in 1.0 it's ok if the client does not send us the
          // the id header
          dest
        } else {
          die("The id header is missing from the SUBSCRIBE frame");
        }

    }

    val topic = destination.getDomain == Router.TOPIC_DOMAIN
    var persistent = get(headers, PERSISTENT).map( _ == TRUE ).getOrElse(false)

    val ack = get(headers, ACK_MODE) match {
      case None=> new AutoAckHandler
      case Some(x)=> x match {
        case ACK_MODE_AUTO=>new AutoAckHandler
        case ACK_MODE_NONE=>new AutoAckHandler
        case ACK_MODE_CLIENT=> new SessionAckHandler
        case ACK_MODE_SESSION=> new SessionAckHandler
        case ACK_MODE_MESSAGE=> new MessageAckHandler
        case ack:AsciiBuffer =>
          die("Unsuported ack mode: "+ack);
      }
    }

    val selector = get(headers, SELECTOR) match {
      case None=> null
      case Some(x)=> x
        try {
          (x, SelectorParser.parse(x.utf8.toString))
        } catch {
          case e:FilterException =>
            die("Invalid selector expression: "+e.getMessage)
        }
    }

    if ( consumers.contains(id) ) {
      die("A subscription with identified with '"+id+"' allready exists")
    }

    val binding: BindingDTO = if( topic && !persistent ) {
      null
    } else {
      // Controls how the created queue gets bound
      // to the destination name space (this is used to
      // recover the queue on restart and rebind it the
      // way again)
      if (topic) {
        val rc = new DurableSubscriptionBindingDTO
        rc.destination = destination.getName.toString
        // TODO:
        // rc.client_id =
        rc.subscription_id = if( persistent ) id else null
        rc.filter = if (selector == null) null else selector._1
        rc
      } else {
        val rc = new PointToPointBindingDTO
        rc.destination = destination.getName.toString
        rc
      }
    }

    val consumer = new StompConsumer(subscription_id, destination, ack, selector, binding);
    consumers += (id -> consumer)

    if( binding==null ) {

      // consumer is bind bound as a topic
      reset {
        host.router.bind(destination, consumer)
        send_receipt(headers)
        consumer.release
      }

    } else {
      reset {
        // create a queue and bind the consumer to it.
        val x= host.router.create_queue(binding)
        x match {
          case Some(queue:Queue) =>
            queue.bind(consumer::Nil)
            send_receipt(headers)
            consumer.release
          case None => async_die("case not yet implemented.")
        }
      }
    }
  }

  def on_stomp_unsubscribe(headers:HeaderMap):Unit = {

    var persistent = get(headers, PERSISTENT).map( _ == TRUE ).getOrElse(false)

    val id = get(headers, ID).getOrElse {
      if( protocol_version eq V1_0 ) {
        // in 1.0 it's ok if the client does not send us the
        // the id header, the destination header must be set
        get(headers, DESTINATION) match {
          case Some(dest)=> dest
          case None=>
            die("destination not set.")
        }
      } else {
        die("The id header is missing from the UNSUBSCRIBE frame");
      }
    }

    consumers.get(id) match {
      case None=>
        die("The subscription '%s' not found.".format(id))
      case Some(consumer)=>
        // consumer.close
        if( consumer.binding==null ) {
          host.router.unbind(consumer.destination, consumer)
          send_receipt(headers)
        } else {

          reset {
            val queue = host.router.get_queue(consumer.binding)
            queue.foreach( _.unbind(consumer::Nil) )
          }

          if( persistent && consumer.binding!=null ) {
            reset {
              val sucess = host.router.destroy_queue(consumer.binding)
              send_receipt(headers)
            }
          } else {
            send_receipt(headers)
          }

        }

    }
  }

  def on_stomp_ack(frame:StompFrame):Unit = {
    val headers = frame.headers
    val messageId = get(headers, MESSAGE_ID).getOrElse(die("message id header not set"))

    val subscription_id = get(headers, SUBSCRIPTION);
    val handler = subscription_id match {
      case None=>
        if( !(protocol_version eq V1_0) ) {
          die("The subscription header is required")
        }
        connection_ack_handlers.get(messageId).orElse(die("Not expecting ack for message id '%s'".format(messageId)))
      case Some(id) =>
        consumers.get(id).map(_.ack_handler).orElse(die("The subscription '%s' does not exist".format(id)))
    }

    handler.foreach{ handler=>
      get(headers, TRANSACTION) match {
        case None=>
          handler.perform_ack(messageId, null)
        case Some(txid)=>
          get_or_create_tx_queue(txid).add{ uow=>
            handler.perform_ack(messageId, uow)
          }
      }
      send_receipt(headers)
    }
  }

  override def onTransportFailure(error: IOException) = {
    if( !connection.stopped ) {
      suspendRead("shutdown")
      debug(error, "Shutting connection down due to: %s", error)
      super.onTransportFailure(error);
    }
  }


  def require_transaction_header[T](headers:HeaderMap):AsciiBuffer = {
    get(headers, TRANSACTION).getOrElse(die("transaction header not set"))
  }

  def on_stomp_begin(headers:HeaderMap) = {
    create_tx_queue(require_transaction_header(headers))
    send_receipt(headers)
  }

  def on_stomp_commit(headers:HeaderMap) = {
    remove_tx_queue(require_transaction_header(headers)).commit {
      send_receipt(headers)
    }
  }

  def on_stomp_abort(headers:HeaderMap) = {
    remove_tx_queue(require_transaction_header(headers)).rollback
    send_receipt(headers)
  }


  def send_receipt(headers:HeaderMap):Unit = {
    get(headers, RECEIPT_REQUESTED) match {
      case Some(receipt)=>
        dispatchQueue <<| ^{
          connection_sink.offer(StompFrame(RECEIPT, List((RECEIPT_ID, receipt))))
        }
      case None=>
    }
  }

  class TransactionQueue {
    // TODO: eventually we want to back this /w a broker Queue which
    // can provides persistence and memory swapping.

    val queue = ListBuffer[(StoreUOW)=>Unit]()

    def add(proc:(StoreUOW)=>Unit):Unit = {
      queue += proc
    }

    def commit(onComplete: => Unit) = {

      val uow = if( host.store!=null ) {
        host.store.createStoreUOW
      } else {
        null
      }

      queue.foreach{ _(uow) }
      if( uow!=null ) {
        uow.onComplete(^{
          onComplete
        })
        uow.release
      } else {
        onComplete
      }

    }

    def rollback = {
      queue.clear
    }

  }

  val transactions = HashMap[AsciiBuffer, TransactionQueue]()

  def create_tx_queue(txid:AsciiBuffer):TransactionQueue = {
    if ( transactions.contains(txid) ) {
      die("transaction allready started")
    } else {
      val queue = new TransactionQueue
      transactions.put(txid, queue)
      queue
    }
  }

  def get_or_create_tx_queue(txid:AsciiBuffer):TransactionQueue = {
    transactions.getOrElseUpdate(txid, new TransactionQueue)
  }

  def remove_tx_queue(txid:AsciiBuffer):TransactionQueue = {
    transactions.remove(txid).getOrElse(die("transaction not active: %d".format(txid)))
  }

}

