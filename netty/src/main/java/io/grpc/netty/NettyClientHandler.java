/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;
import static io.netty.util.CharsetUtil.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ClientTransport.PingCallback;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.DefaultHttp2LocalFlowController;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.StreamBufferingEncoder;
import io.netty.handler.logging.LogLevel;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 */
class NettyClientHandler extends AbstractNettyHandler {
  private static final Logger logger = Logger.getLogger(NettyClientHandler.class.getName());

  /**
   * A message that simply passes through the channel without any real processing. It is useful to
   * check if buffers have been drained and test the health of the channel in a single operation.
   */
  static final Object NOOP_MESSAGE = new Object();

  /**
   * Status used when the transport has exhausted the number of streams.
   */
  private static final Status EXHAUSTED_STREAMS_STATUS =
          Status.UNAVAILABLE.withDescription("Stream IDs have been exhausted");

  private final Http2Connection.PropertyKey streamKey;
  private final ClientTransportLifecycleManager lifecycleManager;
  private final Ticker ticker;
  private final Random random = new Random();
  private WriteQueue clientWriteQueue;
  private Http2Ping ping;

  static NettyClientHandler newHandler(ClientTransportLifecycleManager lifecycleManager,
                                       int flowControlWindow, int maxHeaderListSize,
                                       Ticker ticker) {
    Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive");
    DefaultHttp2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder(true, 32);
    try {
      headersDecoder.headerTable().maxHeaderListSize(maxHeaderListSize);
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
    Http2FrameReader frameReader = new DefaultHttp2FrameReader(headersDecoder);
    Http2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
    Http2Connection connection = new DefaultHttp2Connection(false);
    return newHandler(
        connection, frameReader, frameWriter, lifecycleManager, flowControlWindow, ticker);
  }

  @VisibleForTesting
  static NettyClientHandler newHandler(Http2Connection connection,
                                       Http2FrameReader frameReader,
                                       Http2FrameWriter frameWriter,
                                       ClientTransportLifecycleManager lifecycleManager,
                                       int flowControlWindow,
                                       Ticker ticker) {
    Preconditions.checkNotNull(connection, "connection");
    Preconditions.checkNotNull(frameReader, "frameReader");
    Preconditions.checkNotNull(lifecycleManager, "lifecycleManager");
    Preconditions.checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
    Preconditions.checkNotNull(ticker, "ticker");

    Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyClientHandler.class);
    frameReader = new Http2InboundFrameLogger(frameReader, frameLogger);
    frameWriter = new Http2OutboundFrameLogger(frameWriter, frameLogger);

    StreamBufferingEncoder encoder = new StreamBufferingEncoder(
        new DefaultHttp2ConnectionEncoder(connection, frameWriter));

    // Create the local flow controller configured to auto-refill the connection window.
    connection.local().flowController(new DefaultHttp2LocalFlowController(connection,
            DEFAULT_WINDOW_UPDATE_RATIO, true));

    Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder,
        frameReader);

    Http2Settings settings = new Http2Settings();
    settings.pushEnabled(false);
    settings.initialWindowSize(flowControlWindow);
    settings.maxConcurrentStreams(0);

    return new NettyClientHandler(decoder, encoder, settings, lifecycleManager, ticker);
  }

  private NettyClientHandler(Http2ConnectionDecoder decoder,
                             StreamBufferingEncoder encoder, Http2Settings settings,
                             ClientTransportLifecycleManager lifecycleManager,
                             Ticker ticker) {
    super(decoder, encoder, settings);
    this.lifecycleManager = lifecycleManager;
    this.ticker = ticker;

    // Set the frame listener on the decoder.
    decoder().frameListener(new FrameListener());

    Http2Connection connection = encoder.connection();
    streamKey = connection.newKey();
    connection.addListener(new Http2ConnectionAdapter() {
      @Override
      public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        goingAway(statusFromGoAway(errorCode, ByteBufUtil.getBytes(debugData)));
      }

      @Override
      public void onStreamAdded(Http2Stream stream) {
        NettyClientHandler.this.lifecycleManager.notifyInUse(true);
      }

      @Override
      public void onStreamRemoved(Http2Stream stream) {
        if (connection().numActiveStreams() == 0) {
          NettyClientHandler.this.lifecycleManager.notifyInUse(false);
        }
      }
    });
  }

  /**
   * Handler for commands sent from the stream.
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
          throws Exception {
    if (msg instanceof CreateStreamCommand) {
      createStream((CreateStreamCommand) msg, promise);
    } else if (msg instanceof SendGrpcFrameCommand) {
      sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
    } else if (msg instanceof CancelClientStreamCommand) {
      cancelStream(ctx, (CancelClientStreamCommand) msg, promise);
    } else if (msg instanceof RequestMessagesCommand) {
      ((RequestMessagesCommand) msg).requestMessages();
    } else if (msg instanceof SendPingCommand) {
      sendPingFrame(ctx, (SendPingCommand) msg, promise);
    } else if (msg instanceof GracefulCloseCommand) {
      gracefulClose(ctx, (GracefulCloseCommand) msg, promise);
    } else if (msg instanceof ForcefulCloseCommand) {
      forcefulClose(ctx, (ForcefulCloseCommand) msg, promise);
    } else if (msg == NOOP_MESSAGE) {
      ctx.write(Unpooled.EMPTY_BUFFER, promise);
    } else {
      throw new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
    }
  }

  void startWriteQueue(Channel channel) {
    clientWriteQueue = new WriteQueue(channel);
  }

  WriteQueue getWriteQueue() {
    return clientWriteQueue;
  }

  /**
   * Returns the given processed bytes back to inbound flow control.
   */
  void returnProcessedBytes(Http2Stream stream, int bytes) {
    try {
      decoder().flowController().consumeBytes(stream, bytes);
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void onHeadersRead(int streamId, Http2Headers headers, boolean endStream) {
    NettyClientStream stream = clientStream(requireHttp2Stream(streamId));
    stream.transportHeadersReceived(headers, endStream);
  }

  /**
   * Handler for an inbound HTTP/2 DATA frame.
   */
  private void onDataRead(int streamId, ByteBuf data, boolean endOfStream) {
    NettyClientStream stream = clientStream(requireHttp2Stream(streamId));
    stream.transportDataReceived(data, endOfStream);
  }

  /**
   * Handler for an inbound HTTP/2 RST_STREAM frame, terminating a stream.
   */
  private void onRstStreamRead(int streamId, long errorCode) {
    NettyClientStream stream = clientStream(connection().stream(streamId));
    if (stream != null) {
      Status status = GrpcUtil.Http2Error.statusForCode((int) errorCode)
          .augmentDescription("Received Rst Stream");
      stream.transportReportStatus(status, false /*stop delivery*/, new Metadata());
    }
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    logger.fine("Network channel being closed by the application.");
    lifecycleManager.notifyShutdown(
        Status.UNAVAILABLE.withDescription("Transport closed for unknown reason"));
    super.close(ctx, promise);
  }

  /**
   * Handler for the Channel shutting down.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      logger.fine("Network channel is closed");
      lifecycleManager.notifyShutdown(
          Status.UNAVAILABLE.withDescription("Network closed for unknown reason"));
      cancelPing(lifecycleManager.getShutdownThrowable());
      // Report status to the application layer for any open streams
      connection().forEachActiveStream(new Http2StreamVisitor() {
        @Override
        public boolean visit(Http2Stream stream) throws Http2Exception {
          NettyClientStream clientStream = clientStream(stream);
          if (clientStream != null) {
            clientStream.transportReportStatus(
                lifecycleManager.getShutdownStatus(), false, new Metadata());
          }
          return true;
        }
      });
    } finally {
      // Close any open streams
      super.channelInactive(ctx);
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause,
      Http2Exception http2Ex) {
    logger.log(Level.FINE, "Caught a connection error", cause);
    lifecycleManager.notifyShutdown(Utils.statusFromThrowable(cause));
    // Parent class will shut down the Channel
    super.onConnectionError(ctx, cause, http2Ex);
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, Throwable cause,
      Http2Exception.StreamException http2Ex) {
    // Close the stream with a status that contains the cause.
    NettyClientStream stream = clientStream(connection().stream(http2Ex.streamId()));
    if (stream != null) {
      stream.transportReportStatus(Utils.statusFromThrowable(cause), false, new Metadata());
    } else {
      logger.log(Level.FINE, "Stream error for unknown stream " + http2Ex.streamId(), cause);
    }

    // Delegate to the base class to send a RST_STREAM.
    super.onStreamError(ctx, cause, http2Ex);
  }

  @Override
  protected boolean isGracefulShutdownComplete() {
    // Only allow graceful shutdown to complete after all pending streams have completed.
    return super.isGracefulShutdownComplete()
        && ((StreamBufferingEncoder) encoder()).numBufferedStreams() == 0;
  }

  /**
   * Attempts to create a new stream from the given command. If there are too many active streams,
   * the creation request is queued.
   */
  private void createStream(CreateStreamCommand command, final ChannelPromise promise)
          throws Exception {
    if (lifecycleManager.getShutdownThrowable() != null) {
      // The connection is going away, just terminate the stream now.
      promise.setFailure(lifecycleManager.getShutdownThrowable());
      return;
    }

    // Get the stream ID for the new stream.
    final int streamId;
    try {
      streamId = incrementAndGetNextStreamId();
    } catch (StatusException e) {
      // Stream IDs have been exhausted for this connection. Fail the promise immediately.
      promise.setFailure(e);

      // Initiate a graceful shutdown if we haven't already.
      if (!connection().goAwaySent()) {
        logger.fine("Stream IDs have been exhausted for this connection. "
                + "Initiating graceful shutdown of the connection.");
        lifecycleManager.notifyShutdown(e.getStatus());
        close(ctx(), ctx().newPromise());
      }
      return;
    }

    final NettyClientStream stream = command.stream();
    final Http2Headers headers = command.headers();
    stream.id(streamId);

    // Create an intermediate promise so that we can intercept the failure reported back to the
    // application.
    ChannelPromise tempPromise = ctx().newPromise();
    encoder().writeHeaders(ctx(), streamId, headers, 0, false, tempPromise)
            .addListener(new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                  // The http2Stream will be null in case a stream buffered in the encoder
                  // was canceled via RST_STREAM.
                  Http2Stream http2Stream = connection().stream(streamId);
                  if (http2Stream != null) {
                    http2Stream.setProperty(streamKey, stream);

                    // Attach the client stream to the HTTP/2 stream object as user data.
                    stream.setHttp2Stream(http2Stream);
                  }
                  // Otherwise, the stream has been cancelled and Netty is sending a
                  // RST_STREAM frame which causes it to purge pending writes from the
                  // flow-controller and delete the http2Stream. The stream listener has already
                  // been notified of cancellation so there is nothing to do.

                  // Just forward on the success status to the original promise.
                  promise.setSuccess();
                } else {
                  final Throwable cause = future.cause();
                  if (cause instanceof StreamBufferingEncoder.Http2GoAwayException) {
                    StreamBufferingEncoder.Http2GoAwayException e =
                        (StreamBufferingEncoder.Http2GoAwayException) cause;
                    lifecycleManager.notifyShutdown(statusFromGoAway(e.errorCode(), e.debugData()));
                    promise.setFailure(lifecycleManager.getShutdownThrowable());
                  } else {
                    promise.setFailure(cause);
                  }
                }
              }
            });
  }

  /**
   * Cancels this stream.
   */
  private void cancelStream(ChannelHandlerContext ctx, CancelClientStreamCommand cmd,
      ChannelPromise promise) {
    NettyClientStream stream = cmd.stream();
    stream.transportReportStatus(cmd.reason(), true, new Metadata());
    encoder().writeRstStream(ctx, stream.id(), Http2Error.CANCEL.code(), promise);
  }

  /**
   * Sends the given GRPC frame for the stream.
   */
  private void sendGrpcFrame(ChannelHandlerContext ctx, SendGrpcFrameCommand cmd,
      ChannelPromise promise) {
    // Call the base class to write the HTTP/2 DATA frame.
    // Note: no need to flush since this is handled by the outbound flow controller.
    encoder().writeData(ctx, cmd.streamId(), cmd.content(), 0, cmd.endStream(), promise);
  }

  /**
   * Sends a PING frame. If a ping operation is already outstanding, the callback in the message is
   * registered to be called when the existing operation completes, and no new frame is sent.
   */
  private void sendPingFrame(ChannelHandlerContext ctx, SendPingCommand msg,
      ChannelPromise promise) {
    // Don't check lifecycleManager.getShutdownStatus() since we want to allow pings after shutdown
    // but before termination. After termination, messages will no longer arrive because the
    // pipeline clears all handlers on channel close.

    PingCallback callback = msg.callback();
    Executor executor = msg.executor();
    // we only allow one outstanding ping at a time, so just add the callback to
    // any outstanding operation
    if (ping != null) {
      promise.setSuccess();
      ping.addCallback(callback, executor);
      return;
    }

    // Use a new promise to prevent calling the callback twice on write failure: here and in
    // NettyClientTransport.ping(). It may appear strange, but it will behave the same as if
    // ping != null above.
    promise.setSuccess();
    promise = ctx().newPromise();
    // set outstanding operation
    long data = random.nextLong();
    ByteBuf buffer = ctx.alloc().buffer(8);
    buffer.writeLong(data);
    Stopwatch stopwatch = Stopwatch.createStarted(ticker);
    ping = new Http2Ping(data, stopwatch);
    ping.addCallback(callback, executor);
    // and then write the ping
    encoder().writePing(ctx, false, buffer, promise);
    ctx.flush();
    final Http2Ping finalPing = ping;
    promise.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          finalPing.failed(future.cause());
          if (ping == finalPing) {
            ping = null;
          }
        }
      }
    });
  }

  private void gracefulClose(ChannelHandlerContext ctx, GracefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    lifecycleManager.notifyShutdown(msg.getStatus());
    // Explicitly flush to create any buffered streams before sending GOAWAY.
    // TODO(ejona): determine if the need to flush is a bug in Netty
    flush(ctx);
    close(ctx, promise);
  }

  private void forcefulClose(final ChannelHandlerContext ctx, final ForcefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    lifecycleManager.notifyShutdown(
        Status.UNAVAILABLE.withDescription("Channel requested transport to shut down"));
    close(ctx, promise);
    connection().forEachActiveStream(new Http2StreamVisitor() {
      @Override
      public boolean visit(Http2Stream stream) throws Http2Exception {
        NettyClientStream clientStream = clientStream(stream);
        if (clientStream != null) {
          clientStream.transportReportStatus(msg.getStatus(), true, new Metadata());
          resetStream(ctx, stream.id(), Http2Error.CANCEL.code(), ctx.newPromise());
        }
        stream.close();
        return true;
      }
    });
  }

  /**
   * Handler for a GOAWAY being either sent or received. Fails any streams created after the
   * last known stream.
   */
  private void goingAway(Status status) {
    lifecycleManager.notifyShutdown(status);
    final Status goAwayStatus = lifecycleManager.getShutdownStatus();
    final int lastKnownStream = connection().local().lastStreamKnownByPeer();
    try {
      connection().forEachActiveStream(new Http2StreamVisitor() {
        @Override
        public boolean visit(Http2Stream stream) throws Http2Exception {
          if (stream.id() > lastKnownStream) {
            NettyClientStream clientStream = clientStream(stream);
            if (clientStream != null) {
              clientStream.transportReportStatus(goAwayStatus, false, new Metadata());
            }
            stream.close();
          }
          return true;
        }
      });
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void cancelPing(Throwable t) {
    if (ping != null) {
      ping.failed(t);
      ping = null;
    }
  }

  private Status statusFromGoAway(long errorCode, byte[] debugData) {
    Status status = GrpcUtil.Http2Error.statusForCode((int) errorCode)
        .augmentDescription("Received Goaway");
    if (debugData != null && debugData.length > 0) {
      // If a debug message was provided, use it.
      String msg = new String(debugData, UTF_8);
      status = status.augmentDescription(msg);
    }
    return status;
  }

  /**
   * Gets the client stream associated to the given HTTP/2 stream object.
   */
  private NettyClientStream clientStream(Http2Stream stream) {
    return stream == null ? null : (NettyClientStream) stream.getProperty(streamKey);
  }

  private int incrementAndGetNextStreamId() throws StatusException {
    int nextStreamId = connection().local().incrementAndGetNextStreamId();
    if (nextStreamId < 0) {
      logger.fine("Stream IDs have been exhausted for this connection. "
              + "Initiating graceful shutdown of the connection.");
      throw EXHAUSTED_STREAMS_STATUS.asException();
    }
    return nextStreamId;
  }

  private Http2Stream requireHttp2Stream(int streamId) {
    Http2Stream stream = connection().stream(streamId);
    if (stream == null) {
      // This should never happen.
      throw new AssertionError("Stream does not exist: " + streamId);
    }
    return stream;
  }

  private class FrameListener extends Http2FrameAdapter {
    private boolean firstSettings = true;

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
      if (firstSettings) {
        firstSettings = false;
        lifecycleManager.notifyReady();
      }
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
        boolean endOfStream) throws Http2Exception {
      NettyClientHandler.this.onDataRead(streamId, data, endOfStream);
      return padding;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx,
        int streamId,
        Http2Headers headers,
        int streamDependency,
        short weight,
        boolean exclusive,
        int padding,
        boolean endStream) throws Http2Exception {
      NettyClientHandler.this.onHeadersRead(streamId, headers, endStream);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
        throws Http2Exception {
      NettyClientHandler.this.onRstStreamRead(streamId, errorCode);
    }

    @Override public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data)
        throws Http2Exception {
      Http2Ping p = ping;
      if (p != null) {
        long ackPayload = data.readLong();
        if (p.payload() == ackPayload) {
          p.complete();
          ping = null;
        } else {
          logger.log(Level.WARNING, String.format("Received unexpected ping ack. "
              + "Expecting %d, got %d", p.payload(), ackPayload));
        }
      } else {
        logger.warning("Received unexpected ping ack. No ping outstanding");
      }
    }
  }
}
