/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_SPLITER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Codec;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test for {@link ClientCallImpl}.
 */
@RunWith(JUnit4.class)
public class ClientCallImplTest {

  private static final MethodDescriptor<Void, Void> DESCRIPTOR = MethodDescriptor.create(
      MethodType.UNARY,
      "service/method",
      new TestMarshaller<Void>(),
      new TestMarshaller<Void>());

  private final FakeClock fakeClock = new FakeClock();
  private final ScheduledExecutorService deadlineCancellationExecutor =
      fakeClock.scheduledExecutorService;
  private final DecompressorRegistry decompressorRegistry =
      DecompressorRegistry.getDefaultInstance().with(new Codec.Gzip(), true);
  private final MethodDescriptor<Void, Void> method = MethodDescriptor.create(
      MethodType.UNARY,
      "service/method",
      new TestMarshaller<Void>(),
      new TestMarshaller<Void>());

  @Mock private ClientStreamListener streamListener;
  @Mock private ClientTransport clientTransport;
  @Captor private ArgumentCaptor<Status> statusCaptor;

  @Mock
  private ClientTransport transport;

  @Mock
  private ClientTransportProvider provider;

  @Mock
  private ClientStream stream;

  @Mock
  private ClientCall.Listener<Void> callListener;

  @Captor
  private ArgumentCaptor<ClientStreamListener> listenerArgumentCaptor;

  @Captor
  private ArgumentCaptor<Status> statusArgumentCaptor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(provider.get(any(CallOptions.class))).thenReturn(transport);
    when(transport.newStream(any(MethodDescriptor.class), any(Metadata.class),
        any(CallOptions.class))).thenReturn(stream);
  }

  @After
  public void tearDown() {
    Context.ROOT.attach();
  }

  @Test
  public void exceptionInOnMessageTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        executor,
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.headersRead(new Metadata());

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onMessage(any(Void.class));

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When messageRead is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener.messageRead(new ByteArrayInputStream(new byte[]{}));
    streamListener.closed(Status.OK, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(), Matchers.isA(Metadata.class));
    assertThat(statusArgumentCaptor.getValue().getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(statusArgumentCaptor.getValue().getCause()).isSameAs(failure);
    verify(stream).cancel(statusArgumentCaptor.getValue());
  }

  @Test
  public void exceptionInOnHeadersTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        executor,
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onHeaders(any(Metadata.class));

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When headersRead is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener.headersRead(new Metadata());
    streamListener.closed(Status.OK, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(), Matchers.isA(Metadata.class));
    assertThat(statusArgumentCaptor.getValue().getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(statusArgumentCaptor.getValue().getCause()).isSameAs(failure);
    verify(stream).cancel(statusArgumentCaptor.getValue());
  }

  @Test
  public void exceptionInOnReadyTakesPrecedenceOverServer() {
    DelayedExecutor executor = new DelayedExecutor();
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        executor,
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);
    call.start(callListener, new Metadata());
    verify(stream).start(listenerArgumentCaptor.capture());
    final ClientStreamListener streamListener = listenerArgumentCaptor.getValue();

    RuntimeException failure = new RuntimeException("bad");
    doThrow(failure).when(callListener).onReady();

    /*
     * In unary calls, the server closes the call right after responding, so the onClose call is
     * queued to run.  When onReady is called, an exception will occur and attempt to cancel the
     * stream.  However, since the server closed it "first" the second exception is lost leading to
     * the call being counted as successful.
     */
    streamListener.onReady();
    streamListener.closed(Status.OK, new Metadata());
    executor.release();

    verify(callListener).onClose(statusArgumentCaptor.capture(), Matchers.isA(Metadata.class));
    assertThat(statusArgumentCaptor.getValue().getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(statusArgumentCaptor.getValue().getCause()).isSameAs(failure);
    verify(stream).cancel(statusArgumentCaptor.getValue());
  }

  @Test
  public void advertisedEncodingsAreSent() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());

    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(transport).newStream(eq(method), metadataCaptor.capture(), same(CallOptions.DEFAULT));
    Metadata actual = metadataCaptor.getValue();

    Set<String> acceptedEncodings =
        ImmutableSet.copyOf(actual.getAll(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY));
    assertEquals(decompressorRegistry.getAdvertisedMessageEncodings(), acceptedEncodings);
  }

  @Test
  public void authorityPropagatedToStream() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT.withAuthority("overridden-authority"),
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());
    verify(stream).setAuthority("overridden-authority");
  }

  @Test
  public void callOptionsPropagatedToTransport() {
    final CallOptions callOptions = CallOptions.DEFAULT.withAuthority("dummy_value");
    final ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        MoreExecutors.directExecutor(),
        callOptions,
        provider,
        deadlineCancellationExecutor)
        .setDecompressorRegistry(decompressorRegistry);
    final Metadata metadata = new Metadata();

    call.start(callListener, metadata);

    verify(transport).newStream(same(method), same(metadata), same(callOptions));
  }

  @Test
  public void authorityNotPropagatedToStream() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        method,
        MoreExecutors.directExecutor(),
        // Don't provide an authority
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);

    call.start(callListener, new Metadata());
    verify(stream, never()).setAuthority(any(String.class));
  }

  @Test
  public void prepareHeaders_userAgentIgnored() {
    Metadata m = new Metadata();
    m.put(GrpcUtil.USER_AGENT_KEY, "batmobile");
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE);

    // User Agent is removed and set by the transport
    assertThat(m.get(GrpcUtil.USER_AGENT_KEY)).isNotNull();
  }

  @Test
  public void prepareHeaders_ignoreIdentityEncoding() {
    Metadata m = new Metadata();
    ClientCallImpl.prepareHeaders(m, decompressorRegistry, Codec.Identity.NONE);

    assertNull(m.get(GrpcUtil.MESSAGE_ENCODING_KEY));
  }

  @Test
  public void prepareHeaders_acceptedEncodingsAdded() {
    Metadata m = new Metadata();
    DecompressorRegistry customRegistry = DecompressorRegistry.emptyInstance()
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "a";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, true)
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "b";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, true)
        .with(new Decompressor() {
          @Override
          public String getMessageEncoding() {
            return "c";
          }

          @Override
          public InputStream decompress(InputStream is) throws IOException {
            return null;
          }
        }, false); // not advertised

    ClientCallImpl.prepareHeaders(m, customRegistry, Codec.Identity.NONE);

    Iterable<String> acceptedEncodings =
        ACCEPT_ENCODING_SPLITER.split(m.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY));

    // Order may be different, since decoder priorities have not yet been implemented.
    assertEquals(ImmutableSet.of("b", "a"), ImmutableSet.copyOf(acceptedEncodings));
  }

  @Test
  public void prepareHeaders_removeReservedHeaders() {
    Metadata m = new Metadata();
    m.put(GrpcUtil.MESSAGE_ENCODING_KEY, "gzip");
    m.put(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY, "gzip");

    ClientCallImpl.prepareHeaders(m, DecompressorRegistry.emptyInstance(), Codec.Identity.NONE);

    assertNull(m.get(GrpcUtil.MESSAGE_ENCODING_KEY));
    assertNull(m.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY));
  }

  @Test
  public void callerContextPropagatedToListener() throws Exception {
    // Attach the context which is recorded when the call is created
    final Context.Key<String> testKey = Context.key("testing");
    Context.current().withValue(testKey, "testValue").attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);

    Context.ROOT.attach();

    // Override the value after creating the call, this should not be seen by callbacks
    Context.current().withValue(testKey, "badValue").attach();

    final AtomicBoolean onHeadersCalled = new AtomicBoolean();
    final AtomicBoolean onMessageCalled = new AtomicBoolean();
    final AtomicBoolean onReadyCalled = new AtomicBoolean();
    final AtomicBoolean observedIncorrectContext = new AtomicBoolean();
    final CountDownLatch latch = new CountDownLatch(1);

    call.start(new ClientCall.Listener<Void>() {
      @Override
      public void onHeaders(Metadata headers) {
        onHeadersCalled.set(true);
        checkContext();
      }

      @Override
      public void onMessage(Void message) {
        onMessageCalled.set(true);
        checkContext();
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        checkContext();
        latch.countDown();
      }

      @Override
      public void onReady() {
        onReadyCalled.set(true);
        checkContext();
      }

      private void checkContext() {
        if (!"testValue".equals(testKey.get())) {
          observedIncorrectContext.set(true);
        }
      }
    }, new Metadata());

    verify(stream).start(listenerArgumentCaptor.capture());
    ClientStreamListener listener = listenerArgumentCaptor.getValue();
    listener.onReady();
    listener.headersRead(new Metadata());
    listener.messageRead(new ByteArrayInputStream(new byte[0]));
    listener.messageRead(new ByteArrayInputStream(new byte[0]));
    listener.closed(Status.OK, new Metadata());

    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertTrue(onHeadersCalled.get());
    assertTrue(onMessageCalled.get());
    assertTrue(onReadyCalled.get());
    assertFalse(observedIncorrectContext.get());
  }

  @Test
  public void contextCancellationCancelsStream() throws Exception {
    // Attach the context which is recorded when the call is created
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Context previous = cancellableContext.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);

    previous.attach();

    call.start(callListener, new Metadata());

    Throwable t = new Throwable();
    cancellableContext.cancel(t);

    verify(stream, times(1)).cancel(statusArgumentCaptor.capture());

    verify(stream, times(1)).cancel(statusCaptor.capture());
    assertEquals(Status.Code.CANCELLED, statusCaptor.getValue().getCode());
  }

  @Test
  public void contextAlreadyCancelledNotifiesImmediately() throws Exception {
    // Attach the context which is recorded when the call is created
    Context.CancellableContext cancellableContext = Context.current().withCancellation();
    Throwable cause = new Throwable();
    cancellableContext.cancel(cause);
    Context previous = cancellableContext.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor)
        .setDecompressorRegistry(decompressorRegistry);

    previous.attach();

    final SettableFuture<Status> statusFuture = SettableFuture.create();
    call.start(new ClientCall.Listener<Void>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        statusFuture.set(status);
      }
    }, new Metadata());

    // Caller should receive onClose callback.
    Status status = statusFuture.get(5, TimeUnit.SECONDS);
    assertEquals(Status.Code.CANCELLED, status.getCode());
    assertSame(cause, status.getCause());

    // Following operations should be no-op.
    call.request(1);
    call.sendMessage(null);
    call.halfClose();

    // Stream should never be created.
    verifyZeroInteractions(transport);

    try {
      call.sendMessage(null);
      fail("Call has been cancelled");
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  @Test
  public void deadlineExceededBeforeCallStarted() {
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(0, TimeUnit.SECONDS);
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        new SerializingExecutor(Executors.newSingleThreadExecutor()),
        callOptions,
        provider,
        deadlineCancellationExecutor)
            .setDecompressorRegistry(decompressorRegistry);
    call.start(callListener, new Metadata());
    verify(transport, times(0)).newStream(any(MethodDescriptor.class), any(Metadata.class));
    verify(callListener, timeout(1000)).onClose(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
    verifyZeroInteractions(provider);
  }

  @Test
  public void contextDeadlineShouldBePropagatedInMetadata() {
    long deadlineNanos = TimeUnit.SECONDS.toNanos(1);
    Context context = Context.current().withDeadlineAfter(deadlineNanos, TimeUnit.NANOSECONDS,
        deadlineCancellationExecutor);
    context.attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);

    Metadata headers = new Metadata();

    call.start(callListener, headers);

    assertTrue(headers.containsKey(GrpcUtil.TIMEOUT_KEY));
    Long timeout = headers.get(GrpcUtil.TIMEOUT_KEY);
    assertNotNull(timeout);

    long deltaNanos = TimeUnit.MILLISECONDS.toNanos(400);
    assertTimeoutBetween(timeout, deadlineNanos - deltaNanos, deadlineNanos);
  }

  @Test
  public void contextDeadlineShouldOverrideLargerMetadataTimeout() {
    long deadlineNanos = TimeUnit.SECONDS.toNanos(1);
    Context context = Context.current().withDeadlineAfter(deadlineNanos, TimeUnit.NANOSECONDS,
        deadlineCancellationExecutor);
    context.attach();

    CallOptions callOpts = CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        callOpts,
        provider,
        deadlineCancellationExecutor);

    Metadata headers = new Metadata();

    call.start(callListener, headers);

    assertTrue(headers.containsKey(GrpcUtil.TIMEOUT_KEY));
    Long timeout = headers.get(GrpcUtil.TIMEOUT_KEY);
    assertNotNull(timeout);

    long deltaNanos = TimeUnit.MILLISECONDS.toNanos(400);
    assertTimeoutBetween(timeout, deadlineNanos - deltaNanos, deadlineNanos);
  }

  @Test
  public void contextDeadlineShouldNotOverrideSmallerMetadataTimeout() {
    long deadlineNanos = TimeUnit.SECONDS.toNanos(2);
    Context context = Context.current().withDeadlineAfter(deadlineNanos, TimeUnit.NANOSECONDS,
        deadlineCancellationExecutor);
    context.attach();

    CallOptions callOpts = CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        callOpts,
        provider,
        deadlineCancellationExecutor);

    Metadata headers = new Metadata();

    call.start(callListener, headers);

    assertTrue(headers.containsKey(GrpcUtil.TIMEOUT_KEY));
    Long timeout = headers.get(GrpcUtil.TIMEOUT_KEY);
    assertNotNull(timeout);

    long callOptsNanos = TimeUnit.SECONDS.toNanos(1);
    long deltaNanos = TimeUnit.MILLISECONDS.toNanos(400);
    assertTimeoutBetween(timeout, callOptsNanos - deltaNanos, callOptsNanos);
  }

  @Test
  public void expiredDeadlineCancelsStream_CallOptions() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT.withDeadline(Deadline.after(1000, TimeUnit.MILLISECONDS)),
        provider,
        deadlineCancellationExecutor);

    call.start(callListener, new Metadata());

    fakeClock.forwardMillis(1001);

    verify(stream, times(1)).cancel(statusCaptor.capture());
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
  }

  @Test
  public void expiredDeadlineCancelsStream_Context() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);

    Context.current()
        .withDeadlineAfter(1000, TimeUnit.MILLISECONDS, deadlineCancellationExecutor)
        .attach();

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);

    call.start(callListener, new Metadata());

    fakeClock.forwardMillis(TimeUnit.SECONDS.toMillis(1001));

    verify(stream, times(1)).cancel(statusCaptor.capture());
    assertEquals(Status.Code.DEADLINE_EXCEEDED, statusCaptor.getValue().getCode());
  }

  @Test
  public void streamCancelAbortsDeadlineTimer() {
    fakeClock.forwardTime(System.nanoTime(), TimeUnit.NANOSECONDS);

    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT.withDeadline(Deadline.after(1000, TimeUnit.MILLISECONDS)),
        provider,
        deadlineCancellationExecutor);
    call.start(callListener, new Metadata());
    call.cancel("canceled", null);

    // Run the deadline timer, which should have been cancelled by the previous call to cancel()
    fakeClock.forwardMillis(1001);

    verify(stream, times(1)).cancel(statusCaptor.capture());

    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
  }

  /**
   * Without a context or call options deadline,
   * a timeout should not be set in metadata.
   */
  @Test
  public void timeoutShouldNotBeSet() {
    ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);

    Metadata headers = new Metadata();

    call.start(callListener, headers);

    assertFalse(headers.containsKey(GrpcUtil.TIMEOUT_KEY));
  }

  @Test
  public void cancelInOnMessageShouldInvokeStreamCancel() throws Exception {
    final ClientCallImpl<Void, Void> call = new ClientCallImpl<Void, Void>(
        DESCRIPTOR,
        MoreExecutors.directExecutor(),
        CallOptions.DEFAULT,
        provider,
        deadlineCancellationExecutor);
    final Exception cause = new Exception();
    ClientCall.Listener<Void> callListener =
        new ClientCall.Listener<Void>() {
          @Override
          public void onMessage(Void message) {
            call.cancel("foo", cause);
          }
        };

    call.start(callListener, new Metadata());
    call.halfClose();
    call.request(1);

    verify(stream).start(listenerArgumentCaptor.capture());
    ClientStreamListener streamListener = listenerArgumentCaptor.getValue();
    streamListener.onReady();
    streamListener.headersRead(new Metadata());
    streamListener.messageRead(new ByteArrayInputStream(new byte[0]));
    verify(stream).cancel(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.CANCELLED.getCode(), status.getCode());
    assertEquals("foo", status.getDescription());
    assertSame(cause, status.getCause());
  }

  private static class TestMarshaller<T> implements Marshaller<T> {
    @Override
    public InputStream stream(T value) {
      return null;
    }

    @Override
    public T parse(InputStream stream) {
      return null;
    }
  }

  private static void assertTimeoutBetween(long timeout, long from, long to) {
    assertTrue("timeout: " + timeout + " ns", timeout <= to);
    assertTrue("timeout: " + timeout + " ns", timeout >= from);
  }

  private static final class DelayedExecutor implements Executor {
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<Runnable>();

    @Override
    public void execute(Runnable command) {
      commands.add(command);
    }

    void release() {
      while (!commands.isEmpty()) {
        commands.poll().run();
      }
    }
  }
}

