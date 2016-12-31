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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.netty.Utils.CONTENT_TYPE_GRPC;
import static io.grpc.netty.Utils.CONTENT_TYPE_HEADER;
import static io.grpc.netty.Utils.HTTP_METHOD;
import static io.grpc.netty.Utils.TE_HEADER;
import static io.grpc.netty.Utils.TE_TRAILERS;
import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ServerTransportListener;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
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
import io.netty.handler.logging.LogLevel;
import io.netty.util.ReferenceCountUtil;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Server-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 */
class NettyServerHandler extends AbstractNettyHandler {
  private static Logger logger = Logger.getLogger(NettyServerHandler.class.getName());

  private final Http2Connection.PropertyKey streamKey;
  private final ServerTransportListener transportListener;
  private final int maxMessageSize;
  private Throwable connectionError;
  private boolean teWarningLogged;
  private WriteQueue serverWriteQueue;

  static NettyServerHandler newHandler(ServerTransportListener transportListener,
                                       int maxStreams,
                                       int flowControlWindow,
                                       int maxHeaderListSize,
                                       int maxMessageSize) {
    Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive");
    Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyServerHandler.class);
    DefaultHttp2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder(true, 32);
    try {
      headersDecoder.headerTable().maxHeaderListSize(maxHeaderListSize);
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
    Http2FrameReader frameReader = new Http2InboundFrameLogger(
        new DefaultHttp2FrameReader(headersDecoder), frameLogger);
    Http2FrameWriter frameWriter =
        new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), frameLogger);
    return newHandler(frameReader, frameWriter, transportListener, maxStreams, flowControlWindow,
        maxMessageSize);
  }

  @VisibleForTesting
  static NettyServerHandler newHandler(Http2FrameReader frameReader, Http2FrameWriter frameWriter,
                                       ServerTransportListener transportListener,
                                       int maxStreams,
                                       int flowControlWindow,
                                       int maxMessageSize) {
    Preconditions.checkArgument(maxStreams > 0, "maxStreams must be positive");
    Preconditions.checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
    Preconditions.checkArgument(maxMessageSize > 0, "maxMessageSize must be positive");

    Http2Connection connection = new DefaultHttp2Connection(true);

    // Create the local flow controller configured to auto-refill the connection window.
    connection.local().flowController(new DefaultHttp2LocalFlowController(connection,
            DEFAULT_WINDOW_UPDATE_RATIO, true));

    Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, frameWriter);
    Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder,
        frameReader);

    Http2Settings settings = new Http2Settings();
    settings.initialWindowSize(flowControlWindow);
    settings.maxConcurrentStreams(maxStreams);

    return new NettyServerHandler(transportListener, decoder, encoder, settings, maxMessageSize);
  }

  private NettyServerHandler(ServerTransportListener transportListener,
                             Http2ConnectionDecoder decoder,
                             Http2ConnectionEncoder encoder, Http2Settings settings,
                             int maxMessageSize) {
    super(decoder, encoder, settings);
    checkArgument(maxMessageSize >= 0, "maxMessageSize must be >= 0");
    this.maxMessageSize = maxMessageSize;

    streamKey = encoder.connection().newKey();
    this.transportListener = checkNotNull(transportListener, "transportListener");

    // Set the frame listener on the decoder.
    decoder().frameListener(new FrameListener());
  }

  @Nullable
  Throwable connectionError() {
    return connectionError;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    serverWriteQueue = new WriteQueue(ctx.channel());
    super.handlerAdded(ctx);
  }

  private void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers)
      throws Http2Exception {
    if (!teWarningLogged && !TE_TRAILERS.equals(headers.get(TE_HEADER))) {
      logger.warning(String.format("Expected header TE: %s, but %s is received. This means "
            + "some intermediate proxy may not support trailers",
            TE_TRAILERS, headers.get(TE_HEADER)));
      teWarningLogged = true;
    }

    try {
      // Verify that the Content-Type is correct in the request.
      verifyContentType(streamId, headers);
      String method = determineMethod(streamId, headers);

      // The Http2Stream object was put by AbstractHttp2ConnectionHandler before calling this
      // method.
      Http2Stream http2Stream = requireHttp2Stream(streamId);

      NettyServerStream.TransportState state =
          new NettyServerStream.TransportState(this, http2Stream, maxMessageSize);
      NettyServerStream stream = new NettyServerStream(ctx.channel(), state);

      Metadata metadata = Utils.convertHeaders(headers);
      transportListener.streamCreated(stream, method, metadata);
      state.onStreamAllocated();
      http2Stream.setProperty(streamKey, state);
    } catch (Http2Exception e) {
      throw e;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onHeadersRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  private void onDataRead(int streamId, ByteBuf data, boolean endOfStream) throws Http2Exception {
    try {
      NettyServerStream.TransportState stream = serverStream(requireHttp2Stream(streamId));
      stream.inboundDataReceived(data, endOfStream);
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onDataRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  private void onRstStreamRead(int streamId) throws Http2Exception {
    try {
      NettyServerStream.TransportState stream = serverStream(connection().stream(streamId));
      if (stream != null) {
        stream.transportReportStatus(Status.CANCELLED);
      }
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onRstStreamRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause,
      Http2Exception http2Ex) {
    logger.log(Level.WARNING, "Connection Error", cause);
    connectionError = cause;
    super.onConnectionError(ctx, cause, http2Ex);
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, Throwable cause,
      StreamException http2Ex) {
    logger.log(Level.WARNING, "Stream Error", cause);
    NettyServerStream.TransportState serverStream = serverStream(
        connection().stream(Http2Exception.streamId(http2Ex)));
    if (serverStream != null) {
      serverStream.transportReportStatus(Utils.statusFromThrowable(cause));
    }
    // TODO(ejona): Abort the stream by sending headers to help the client with debugging.
    // Delegate to the base class to send a RST_STREAM.
    super.onStreamError(ctx, cause, http2Ex);
  }

  /**
   * Handler for the Channel shutting down.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      final Status status =
          Status.UNAVAILABLE.withDescription("connection terminated for unknown reason");
      // Any streams that are still active must be closed
      connection().forEachActiveStream(new Http2StreamVisitor() {
        @Override
        public boolean visit(Http2Stream stream) throws Http2Exception {
          NettyServerStream.TransportState serverStream = serverStream(stream);
          if (serverStream != null) {
            serverStream.transportReportStatus(status);
          }
          return true;
        }
      });
    } finally {
      super.channelInactive(ctx);
    }
  }

  WriteQueue getWriteQueue() {
    return serverWriteQueue;
  }

  /**
   * Handler for commands sent from the stream.
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof SendGrpcFrameCommand) {
      sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
    } else if (msg instanceof SendResponseHeadersCommand) {
      sendResponseHeaders(ctx, (SendResponseHeadersCommand) msg, promise);
    } else if (msg instanceof RequestMessagesCommand) {
      ((RequestMessagesCommand) msg).requestMessages();
    } else if (msg instanceof CancelServerStreamCommand) {
      cancelStream(ctx, (CancelServerStreamCommand) msg, promise);
    } else if (msg instanceof ForcefulCloseCommand) {
      forcefulClose(ctx, (ForcefulCloseCommand) msg, promise);
    } else {
      AssertionError e =
          new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
      ReferenceCountUtil.release(msg);
      promise.setFailure(e);
      throw e;
    }
  }

  /**
   * Returns the given processed bytes back to inbound flow control.
   */
  void returnProcessedBytes(Http2Stream http2Stream, int bytes) {
    try {
      decoder().flowController().consumeBytes(http2Stream, bytes);
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void closeStreamWhenDone(ChannelPromise promise, int streamId) throws Http2Exception {
    final NettyServerStream.TransportState stream = serverStream(requireHttp2Stream(streamId));
    promise.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        stream.complete();
      }
    });
  }

  /**
   * Sends the given gRPC frame to the client.
   */
  private void sendGrpcFrame(ChannelHandlerContext ctx, SendGrpcFrameCommand cmd,
      ChannelPromise promise) throws Http2Exception {
    if (cmd.endStream()) {
      closeStreamWhenDone(promise, cmd.streamId());
    }
    // Call the base class to write the HTTP/2 DATA frame.
    encoder().writeData(ctx, cmd.streamId(), cmd.content(), 0, cmd.endStream(), promise);
  }

  /**
   * Sends the response headers to the client.
   */
  private void sendResponseHeaders(ChannelHandlerContext ctx, SendResponseHeadersCommand cmd,
      ChannelPromise promise) throws Http2Exception {
    if (cmd.endOfStream()) {
      closeStreamWhenDone(promise, cmd.stream().id());
    }
    encoder().writeHeaders(ctx, cmd.stream().id(), cmd.headers(), 0, cmd.endOfStream(), promise);
  }

  private void cancelStream(ChannelHandlerContext ctx, CancelServerStreamCommand cmd,
      ChannelPromise promise) {
    // Notify the listener if we haven't already.
    cmd.stream().transportReportStatus(cmd.reason());
    // Terminate the stream.
    encoder().writeRstStream(ctx, cmd.stream().id(), Http2Error.CANCEL.code(), promise);
  }

  private void forcefulClose(final ChannelHandlerContext ctx, final ForcefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    close(ctx, promise);
    connection().forEachActiveStream(new Http2StreamVisitor() {
      @Override
      public boolean visit(Http2Stream stream) throws Http2Exception {
        NettyServerStream.TransportState serverStream = serverStream(stream);
        if (serverStream != null) {
          serverStream.transportReportStatus(msg.getStatus());
          resetStream(ctx, stream.id(), Http2Error.CANCEL.code(), ctx.newPromise());
        }
        stream.close();
        return true;
      }
    });
  }

  private void verifyContentType(int streamId, Http2Headers headers) throws Http2Exception {
    CharSequence contentType = headers.get(CONTENT_TYPE_HEADER);
    if (contentType == null) {
      throw Http2Exception.streamError(streamId, Http2Error.REFUSED_STREAM,
          "Content-Type is missing from the request");
    }
    String contentTypeString = contentType.toString();
    if (!GrpcUtil.isGrpcContentType(contentTypeString)) {
      throw Http2Exception.streamError(streamId, Http2Error.REFUSED_STREAM,
          "Content-Type '%s' is not supported", contentTypeString);
    }
  }

  private Http2Stream requireHttp2Stream(int streamId) {
    Http2Stream stream = connection().stream(streamId);
    if (stream == null) {
      // This should never happen.
      throw new AssertionError("Stream does not exist: " + streamId);
    }
    return stream;
  }

  private String determineMethod(int streamId, Http2Headers headers) throws Http2Exception {
    if (!HTTP_METHOD.equals(headers.method())) {
      throw Http2Exception.streamError(streamId, Http2Error.REFUSED_STREAM,
          "Method '%s' is not supported", headers.method());
    }
    checkHeader(streamId, headers, CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC);
    // Remove the leading slash of the path and get the fully qualified method name
    CharSequence path = headers.path();
    if (path.charAt(0) != '/') {
      throw Http2Exception.streamError(streamId, Http2Error.REFUSED_STREAM,
          "Malformatted path: %s", path);
    }
    return path.subSequence(1, path.length()).toString();
  }

  private static void checkHeader(int streamId,
                                  Http2Headers headers,
                                  CharSequence header,
                                  CharSequence expectedValue) throws Http2Exception {
    if (!expectedValue.equals(headers.get(header))) {
      throw Http2Exception.streamError(streamId, Http2Error.REFUSED_STREAM,
          "Header '%s'='%s', while '%s' is expected", header, headers.get(header), expectedValue);
    }
  }

  /**
   * Returns the server stream associated to the given HTTP/2 stream object.
   */
  private NettyServerStream.TransportState serverStream(Http2Stream stream) {
    return stream == null ? null : (NettyServerStream.TransportState) stream.getProperty(streamKey);
  }

  private Http2Exception newStreamException(int streamId, Throwable cause) {
    return Http2Exception.streamError(
        streamId, Http2Error.INTERNAL_ERROR, cause, cause.getMessage());
  }

  private class FrameListener extends Http2FrameAdapter {

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
        boolean endOfStream) throws Http2Exception {
      NettyServerHandler.this.onDataRead(streamId, data, endOfStream);
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
      NettyServerHandler.this.onHeadersRead(ctx, streamId, headers);
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
        throws Http2Exception {
      NettyServerHandler.this.onRstStreamRead(streamId);
    }
  }
}
