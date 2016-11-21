package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.finagle.{Failure, Service}
import com.twitter.finagle.stats.{StatsReceiver => FStatsReceiver}
import com.twitter.finagle.transport.Transport
import com.twitter.logging.Logger
import com.twitter.util.{Closable, Future, Return, Stopwatch, Time, Throw}
import io.netty.handler.codec.http2._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.JavaConverters._
import scala.util.control.NoStackTrace

object Netty4ClientDispatcher {
  private val log = Logger.get(getClass.getName)
  private val BaseStreamId = 3 // ID=1 is reserved for HTTP/1 upgrade
  private val MaxStreamId = (math.pow(2, 31) - 1).toInt
}

/**
 * Expose a single HTTP2 connection as a Service.
 *
 * The provided Transport[Http2Frame, Http2Frame] models a single
 * HTTP2 connection.xs
 */
class Netty4ClientDispatcher(
  transport: Transport[Http2Frame, Http2Frame],
  streamStats: Netty4StreamTransport.StatsReceiver
) extends Service[Request, Response] {

  import Netty4ClientDispatcher._

  private[this] val writer = Netty4H2Writer(transport)

  private[this] val _id = new AtomicInteger(BaseStreamId)
  private[this] def nextId(): Int = {
    val id = _id.getAndAdd(2)
    if (id < BaseStreamId || MaxStreamId < id) {
      // If the ID overflows, we can't use this connection anymore, so
      // we try to indicate to the server by sending a GO_AWAY in
      // accordance with the RFC.
      if (closed.compareAndSet(false, true)) writer.goAway(GoAway.ProtocolError)
      throw new IllegalArgumentException("stream id overflow")
    }
    id
  }

  private[this] val streams =
    new ConcurrentHashMap[Int, Netty4StreamTransport[Request, Response]]

  private[this] val closed = new AtomicBoolean(false)

  // Initialize a new Stream; and store it so that a response may be
  // demultiplexed to it.
  private[this] def newStreamTransport(): Netty4StreamTransport[Request, Response] = {
    val id = nextId()
    val stream = Netty4StreamTransport.client(id, writer, streamStats)
    if (streams.putIfAbsent(id, stream) != null) {
      throw new IllegalStateException(s"stream ${stream.streamId} already exists")
    }
    stream.onClose.ensure {
      streams.remove(id, stream); ()
    }
    stream
  }

  /**
   * Continually read frames from the HTTP2 transport. Demultiplex
   * frames from the transport onto a per-stream receive queue.
   */
  private[this] val reading = {
    lazy val loop: Http2Frame => Future[Unit] = {
      case _: Http2GoAwayFrame =>
        if (closed.compareAndSet(false, true)) transport.close()
        else Future.Unit

      case f: Http2StreamFrame =>
        f.streamId match {
          case 0 => writer.goAway(GoAway.ProtocolError)
          case id =>
            streams.get(id) match {
              case null =>
                log.error(s"client dispatcher dropping ${f.name} message on unknown stream ${id}")
                writer.reset(id, Reset.Closed)

              case stream =>
                if (stream.admitRemote(f)) {
                  if (closed.get) Future.Unit
                  else transport.read().flatMap(loop)
                } else {
                  log.error(s"client dispatcher failed to offer ${f.name} on stream ${id}")
                  stream.reset(Reset.Closed)
                }
            }
        }

      case unknown =>
        log.error(s"client dispatcher ignoring ${unknown.name} message on the connection")
        if (closed.get) Future.Unit
        else transport.read().flatMap(loop)
    }

    transport.read().flatMap(loop).onFailure {
      case f@Failure(_) if f.isFlagged(Failure.Interrupted) =>
      case e => log.error(e, "client dispatcher")
    }
  }

  /**
   * Write a request on the underlying connection and return its
   * response when it is received.
   */
  override def apply(req: Request): Future[Response] = {
    val st = newStreamTransport()
    // Stream the request while receiving the response and
    // continue streaming the request until it is complete,
    // canceled,  or the response fails.
    val t0 = Stopwatch.start()
    val writeF = st.write(req)
    writeF.onFailure(st.remoteMsg.raise(_))
    writeF.flatMap { send =>
      send.onFailure(st.remoteMsg.raise)
      st.remoteMsg.onFailure(send.raise)
      st.remoteMsg
    }
  }

  override def close(d: Time): Future[Unit] =
    if (closed.compareAndSet(false, true)) {
      reading.raise(Failure("closed").flagged(Failure.Interrupted))
      writer.goAway(GoAway.NoError, d)
    } else Future.Unit
}
