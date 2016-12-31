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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static io.grpc.netty.NettyTestUtil.messageFrame;
import static io.grpc.netty.Utils.CONTENT_TYPE_GRPC;
import static io.grpc.netty.Utils.CONTENT_TYPE_HEADER;
import static io.grpc.netty.Utils.STATUS_OK;
import static io.netty.util.CharsetUtil.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableListMultimap;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.WriteQueue.QueuedCommand;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Tests for {@link NettyClientStream}.
 */
@RunWith(JUnit4.class)
public class NettyClientStreamTest extends NettyStreamTestBase<NettyClientStream> {
  @Mock
  protected ClientStreamListener listener;

  @Mock
  protected NettyClientHandler handler;

  @SuppressWarnings("unchecked")
  private MethodDescriptor.Marshaller<Void> marshaller = mock(MethodDescriptor.Marshaller.class);

  // Must be initialized before @Before, because it is used by createStream()
  private MethodDescriptor<?, ?> methodDescriptor = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNARY, "/testService/test", marshaller, marshaller);

  @Override
  protected ClientStreamListener listener() {
    return listener;
  }

  @Test
  public void closeShouldSucceed() {
    // Force stream creation.
    stream().id(STREAM_ID);
    stream().halfClose();
    assertTrue(stream().canReceive());
    assertFalse(stream().canSend());
  }

  @Test
  public void cancelShouldSendCommand() {
    // Set stream id to indicate it has been created
    stream().id(STREAM_ID);
    stream().cancel(Status.CANCELLED);
    ArgumentCaptor<CancelClientStreamCommand> commandCaptor =
        ArgumentCaptor.forClass(CancelClientStreamCommand.class);
    verify(writeQueue).enqueue(commandCaptor.capture(), eq(true));
    assertEquals(commandCaptor.getValue().reason(), Status.CANCELLED);
  }

  @Test
  public void deadlineExceededCancelShouldSendCommand() {
    // Set stream id to indicate it has been created
    stream().id(STREAM_ID);
    stream().cancel(Status.DEADLINE_EXCEEDED);
    ArgumentCaptor<CancelClientStreamCommand> commandCaptor =
        ArgumentCaptor.forClass(CancelClientStreamCommand.class);
    verify(writeQueue).enqueue(commandCaptor.capture(), eq(true));
    assertEquals(commandCaptor.getValue().reason(), Status.DEADLINE_EXCEEDED);
  }

  @Test
  public void cancelShouldStillSendCommandIfStreamNotCreatedToCancelCreation() {
    stream().cancel(Status.CANCELLED);
    verify(writeQueue).enqueue(isA(CancelClientStreamCommand.class), eq(true));
  }

  @Test
  public void writeMessageShouldSendRequest() throws Exception {
    // Force stream creation.
    stream().id(STREAM_ID);
    byte[] msg = smallMessage();
    stream.writeMessage(new ByteArrayInputStream(msg));
    stream.flush();
    verify(writeQueue).enqueue(
        eq(new SendGrpcFrameCommand(stream, messageFrame(MESSAGE), false)),
        any(ChannelPromise.class),
        eq(true));
  }

  @Test
  public void writeMessageShouldSendRequestUnknownLength() throws Exception {
    // Force stream creation.
    stream().id(STREAM_ID);
    byte[] msg = smallMessage();
    stream.writeMessage(new BufferedInputStream(new ByteArrayInputStream(msg)));
    stream.flush();
    // Two writes occur, one for the GRPC frame header and the second with the payload
    verify(writeQueue).enqueue(
            eq(new SendGrpcFrameCommand(stream, messageFrame(MESSAGE).slice(0, 5), false)),
            any(ChannelPromise.class),
            eq(false));
    verify(writeQueue).enqueue(
        eq(new SendGrpcFrameCommand(stream, messageFrame(MESSAGE).slice(5, 11), false)),
        any(ChannelPromise.class),
        eq(true));
  }

  @Test
  public void setStatusWithOkShouldCloseStream() {
    stream().id(STREAM_ID);
    stream().transportReportStatus(Status.OK, true, new Metadata());
    verify(listener).closed(same(Status.OK), any(Metadata.class));
    assertTrue(stream.isClosed());
  }

  @Test
  public void setStatusWithErrorShouldCloseStream() {
    Status errorStatus = Status.INTERNAL;
    stream().transportReportStatus(errorStatus, true, new Metadata());
    verify(listener).closed(eq(errorStatus), any(Metadata.class));
    assertTrue(stream.isClosed());
  }

  @Test
  public void setStatusWithOkShouldNotOverrideError() {
    Status errorStatus = Status.INTERNAL;
    stream().transportReportStatus(errorStatus, true, new Metadata());
    stream().transportReportStatus(Status.OK, true, new Metadata());
    verify(listener).closed(any(Status.class), any(Metadata.class));
    assertTrue(stream.isClosed());
  }

  @Test
  public void setStatusWithErrorShouldNotOverridePreviousError() {
    Status errorStatus = Status.INTERNAL;
    stream().transportReportStatus(errorStatus, true, new Metadata());
    stream().transportReportStatus(Status.fromThrowable(new RuntimeException("fake")), true,
        new Metadata());
    verify(listener).closed(any(Status.class), any(Metadata.class));
    assertTrue(stream.isClosed());
  }

  @Override
  @Test
  public void inboundMessageShouldCallListener() throws Exception {
    // Receive headers first so that it's a valid GRPC response.
    stream().id(STREAM_ID);
    stream().transportHeadersReceived(grpcResponseHeaders(), false);
    super.inboundMessageShouldCallListener();
  }

  @Test
  public void inboundHeadersShouldCallListenerHeadersRead() throws Exception {
    stream().id(STREAM_ID);
    Http2Headers headers = grpcResponseHeaders();
    stream().transportHeadersReceived(headers, false);
    verify(listener).headersRead(any(Metadata.class));
  }

  @Test
  public void inboundTrailersClosesCall() throws Exception {
    stream().id(STREAM_ID);
    stream().transportHeadersReceived(grpcResponseHeaders(), false);
    super.inboundMessageShouldCallListener();
    stream().transportHeadersReceived(grpcResponseTrailers(Status.OK), true);
  }

  @Test
  public void inboundStatusShouldSetStatus() throws Exception {
    stream().id(STREAM_ID);

    // Receive headers first so that it's a valid GRPC response.
    stream().transportHeadersReceived(grpcResponseHeaders(), false);

    stream().transportHeadersReceived(grpcResponseTrailers(Status.INTERNAL), true);
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(listener).closed(captor.capture(), any(Metadata.class));
    assertEquals(Status.INTERNAL.getCode(), captor.getValue().getCode());
    assertTrue(stream.isClosed());
  }

  @Test
  public void invalidInboundHeadersCancelStream() throws Exception {
    stream().id(STREAM_ID);
    Http2Headers headers = grpcResponseHeaders();
    headers.set("random", "4");
    headers.remove(CONTENT_TYPE_HEADER);
    // Remove once b/16290036 is fixed.
    headers.status(new AsciiString("500"));
    stream().transportHeadersReceived(headers, false);
    verify(listener, never()).closed(any(Status.class), any(Metadata.class));

    // We are now waiting for 100 bytes of error context on the stream, cancel has not yet been
    // sent
    verify(channel, never()).writeAndFlush(any(CancelClientStreamCommand.class));
    stream().transportDataReceived(Unpooled.buffer(100).writeZero(100), false);
    verify(channel, never()).writeAndFlush(any(CancelClientStreamCommand.class));
    stream().transportDataReceived(Unpooled.buffer(1000).writeZero(1000), false);

    // Now verify that cancel is sent and an error is reported to the listener
    verify(writeQueue).enqueue(any(CancelClientStreamCommand.class), eq(true));
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(listener).closed(captor.capture(), metadataCaptor.capture());
    assertEquals(Status.UNKNOWN.getCode(), captor.getValue().getCode());
    assertEquals("4", metadataCaptor.getValue()
        .get(Metadata.Key.of("random", Metadata.ASCII_STRING_MARSHALLER)));
    assertTrue(stream.isClosed());

  }

  @Test
  public void invalidInboundContentTypeShouldCancelStream() {
    // Set stream id to indicate it has been created
    stream().id(STREAM_ID);
    Http2Headers headers = new DefaultHttp2Headers().status(STATUS_OK).set(CONTENT_TYPE_HEADER,
            new AsciiString("application/bad", UTF_8));
    stream().transportHeadersReceived(headers, false);
    Http2Headers trailers = new DefaultHttp2Headers()
        .set(new AsciiString("grpc-status", UTF_8), new AsciiString("0", UTF_8));
    stream().transportHeadersReceived(trailers, true);
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(listener).closed(captor.capture(), metadataCaptor.capture());
    Status status = captor.getValue();
    assertEquals(Status.Code.UNKNOWN, status.getCode());
    assertTrue(status.getDescription().contains("content-type"));
    assertEquals("application/bad", metadataCaptor.getValue()
        .get(Metadata.Key.of("Content-Type", Metadata.ASCII_STRING_MARSHALLER)));
  }

  @Test
  public void nonGrpcResponseShouldSetStatus() throws Exception {
    stream().transportDataReceived(Unpooled.copiedBuffer(MESSAGE, UTF_8), true);
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(listener).closed(captor.capture(), any(Metadata.class));
    assertEquals(Status.Code.INTERNAL, captor.getValue().getCode());
  }

  @Test
  public void deframedDataAfterCancelShouldBeIgnored() throws Exception {
    stream().id(STREAM_ID);
    // Receive headers first so that it's a valid GRPC response.
    stream().transportHeadersReceived(grpcResponseHeaders(), false);

    // Receive 2 consecutive empty frames. Only one is delivered at a time to the listener.
    stream().transportDataReceived(simpleGrpcFrame(), false);
    stream().transportDataReceived(simpleGrpcFrame(), false);

    // Only allow the first to be delivered.
    stream().request(1);

    // Receive error trailers. The server status will not be processed until after all of the
    // data frames have been processed. Since cancellation will interrupt message delivery,
    // this status will never be processed and the listener will instead only see the
    // cancellation.
    stream().transportHeadersReceived(grpcResponseTrailers(Status.INTERNAL), true);

    // Verify that the first was delivered.
    verify(listener).messageRead(any(InputStream.class));

    // Now set the error status.
    Metadata trailers = Utils.convertTrailers(grpcResponseTrailers(Status.CANCELLED));
    stream().transportReportStatus(Status.CANCELLED, true, trailers);

    // Now allow the delivery of the second.
    stream().request(1);

    // Verify that the listener was only notified of the first message, not the second.
    verify(listener).messageRead(any(InputStream.class));
    verify(listener).closed(eq(Status.CANCELLED), eq(trailers));
  }

  @Test
  public void dataFrameWithEosShouldDeframeAndThenFail() {
    stream().id(STREAM_ID);
    stream().request(1);

    // Receive headers first so that it's a valid GRPC response.
    stream().transportHeadersReceived(grpcResponseHeaders(), false);

    // Receive a DATA frame with EOS set.
    stream().transportDataReceived(simpleGrpcFrame(), true);

    // Verify that the message was delivered.
    verify(listener).messageRead(any(InputStream.class));

    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(listener).closed(captor.capture(), any(Metadata.class));
    assertEquals(Status.Code.INTERNAL, captor.getValue().getCode());
  }

  @Test
  public void setHttp2StreamShouldNotifyReady() {
    listener = mock(ClientStreamListener.class);

    stream = new NettyClientStreamImpl(methodDescriptor, new Metadata(), channel, handler,
        DEFAULT_MAX_MESSAGE_SIZE, AsciiString.of("localhost"), AsciiString.of("http"),
        AsciiString.of("agent"));
    stream.start(listener);
    stream().id(STREAM_ID);
    verify(listener, never()).onReady();
    assertFalse(stream.isReady());
    stream().setHttp2Stream(http2Stream);
    verify(listener).onReady();
    assertTrue(stream.isReady());
  }

  @Test
  public void removeUserAgentFromApplicationHeaders() {
    Metadata metadata = new Metadata();
    metadata.put(GrpcUtil.USER_AGENT_KEY, "bad agent");
    listener = mock(ClientStreamListener.class);
    Mockito.reset(writeQueue);
    when(writeQueue.enqueue(any(QueuedCommand.class), any(boolean.class))).thenReturn(future);

    stream = new NettyClientStreamImpl(methodDescriptor, new Metadata(), channel, handler,
        DEFAULT_MAX_MESSAGE_SIZE, AsciiString.of("localhost"), AsciiString.of("http"),
        AsciiString.of("good agent"));
    stream.start(listener);

    ArgumentCaptor<CreateStreamCommand> cmdCap = ArgumentCaptor.forClass(CreateStreamCommand.class);
    verify(writeQueue).enqueue(cmdCap.capture(), eq(false));
    assertThat(ImmutableListMultimap.copyOf(cmdCap.getValue().headers()))
        .containsEntry(Utils.USER_AGENT, AsciiString.of("good agent"));
  }

  @Override
  protected NettyClientStream createStream() {
    when(handler.getWriteQueue()).thenReturn(writeQueue);
    doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        if (future.isDone()) {
          ((ChannelPromise) invocation.getArguments()[1]).trySuccess();
        }
        return null;
      }
    }).when(writeQueue).enqueue(any(QueuedCommand.class), any(ChannelPromise.class), anyBoolean());
    when(writeQueue.enqueue(any(QueuedCommand.class), anyBoolean())).thenReturn(future);
    NettyClientStream stream = new NettyClientStreamImpl(methodDescriptor, new Metadata(), channel,
        handler, DEFAULT_MAX_MESSAGE_SIZE, AsciiString.of("localhost"), AsciiString.of("http"),
        AsciiString.of("agent"));
    stream.start(listener);
    assertTrue(stream.canSend());
    assertTrue(stream.canReceive());
    stream.id(STREAM_ID);
    stream.setHttp2Stream(http2Stream);
    reset(listener);
    return stream;
  }

  @Override
  protected void sendHeadersIfServer() {}

  @Override
  protected void closeStream() {
    stream().cancel(Status.CANCELLED);
  }

  private ByteBuf simpleGrpcFrame() {
    return Unpooled.wrappedBuffer(new byte[] {0, 0, 0, 0, 2, 3, 14});
  }

  private NettyClientStream stream() {
    return stream;
  }

  private Http2Headers grpcResponseHeaders() {
    return new DefaultHttp2Headers()
        .status(STATUS_OK)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC);
  }

  private Http2Headers grpcResponseTrailers(Status status) {
    Metadata trailers = new Metadata();
    trailers.put(Status.CODE_KEY, status);
    return Utils.convertTrailers(trailers, true);
  }

  class NettyClientStreamImpl extends NettyClientStream {
    NettyClientStreamImpl(MethodDescriptor<?, ?> method, Metadata headers, Channel channel,
        NettyClientHandler handler, int maxMessageSize, AsciiString authority, AsciiString scheme,
        AsciiString userAgent) {
      super(method, headers, channel, handler, maxMessageSize, authority, scheme, userAgent);
    }

    @Override
    protected Status statusFromFailedFuture(ChannelFuture f) {
      return Utils.statusFromThrowable(f.cause());
    }
  }
}
