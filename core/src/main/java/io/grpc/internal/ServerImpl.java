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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.base.Preconditions;

import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.GuardedBy;

/**
 * Default implementation of {@link io.grpc.Server}, for creation by transports.
 *
 * <p>Expected usage (by a theoretical TCP transport):
 * <pre><code>public class TcpTransportServerFactory {
 *   public static Server newServer(Executor executor, HandlerRegistry registry,
 *       String configuration) {
 *     return new ServerImpl(executor, registry, new TcpTransportServer(configuration));
 *   }
 * }</code></pre>
 *
 * <p>Starting the server starts the underlying transport for servicing requests. Stopping the
 * server stops servicing new requests and waits for all connections to terminate.
 */
public final class ServerImpl extends io.grpc.Server {
  private static final ServerStreamListener NOOP_LISTENER = new NoopListener();

  /** Executor for application processing. */
  private Executor executor;
  @GuardedBy("lock") private boolean usingSharedExecutor;
  private final InternalHandlerRegistry registry;
  private final HandlerRegistry fallbackRegistry;
  @GuardedBy("lock") private boolean started;
  @GuardedBy("lock") private boolean shutdown;
  /** non-{@code null} if immediate shutdown has been requested. */
  @GuardedBy("lock") private Status shutdownNowStatus;
  /** {@code true} if ServerListenerImpl.serverShutdown() was called. */
  @GuardedBy("lock") private boolean serverShutdownCallbackInvoked;
  @GuardedBy("lock") private boolean terminated;
  /** Service encapsulating something similar to an accept() socket. */
  private final InternalServer transportServer;
  private final Object lock = new Object();
  @GuardedBy("lock") private boolean transportServerTerminated;
  /** {@code transportServer} and services encapsulating something similar to a TCP connection. */
  @GuardedBy("lock") private final Collection<ServerTransport> transports =
      new HashSet<ServerTransport>();

  private final ScheduledExecutorService timeoutService = SharedResourceHolder.get(TIMER_SERVICE);
  private final Context rootContext;

  private final DecompressorRegistry decompressorRegistry;
  private final CompressorRegistry compressorRegistry;

  /**
   * Construct a server.
   *
   * @param registry the primary method registry
   * @param fallbackRegistry the secondary method registry, used only if the primary registry
   *        doesn't have the method
   * @param executor to call methods on behalf of remote clients
   */
  ServerImpl(Executor executor, InternalHandlerRegistry registry, HandlerRegistry fallbackRegistry,
      InternalServer transportServer, Context rootContext,
      DecompressorRegistry decompressorRegistry, CompressorRegistry compressorRegistry) {
    this.executor = executor;
    this.registry = Preconditions.checkNotNull(registry, "registry");
    this.fallbackRegistry = Preconditions.checkNotNull(fallbackRegistry, "fallbackRegistry");
    this.transportServer = Preconditions.checkNotNull(transportServer, "transportServer");
    // Fork from the passed in context so that it does not propagate cancellation, it only
    // inherits values.
    this.rootContext = Preconditions.checkNotNull(rootContext).fork();
    this.decompressorRegistry = decompressorRegistry;
    this.compressorRegistry = compressorRegistry;
  }

  /**
   * Bind and start the server.
   *
   * @return {@code this} object
   * @throws IllegalStateException if already started
   * @throws IOException if unable to bind
   */
  @Override
  public ServerImpl start() throws IOException {
    synchronized (lock) {
      checkState(!started, "Already started");
      checkState(!shutdown, "Shutting down");
      usingSharedExecutor = executor == null;
      if (usingSharedExecutor) {
        executor = SharedResourceHolder.get(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
      }
      // Start and wait for any port to actually be bound.
      transportServer.start(new ServerListenerImpl());
      started = true;
      return this;
    }
  }

  @Override
  public int getPort() {
    synchronized (lock) {
      checkState(started, "Not started");
      checkState(!terminated, "Already terminated");
      return transportServer.getPort();
    }
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are rejected.
   */
  @Override
  public ServerImpl shutdown() {
    boolean shutdownTransportServer;
    synchronized (lock) {
      if (shutdown) {
        return this;
      }
      shutdown = true;
      shutdownTransportServer = started;
      if (!shutdownTransportServer) {
        transportServerTerminated = true;
        checkForTermination();
      }
    }
    if (shutdownTransportServer) {
      transportServer.shutdown();
    }
    SharedResourceHolder.release(TIMER_SERVICE, timeoutService);
    if (usingSharedExecutor) {
      SharedResourceHolder.release(GrpcUtil.SHARED_CHANNEL_EXECUTOR, (ExecutorService) executor);
    }
    return this;
  }

  @Override
  public ServerImpl shutdownNow() {
    shutdown();
    Collection<ServerTransport> transportsCopy;
    Status nowStatus = Status.UNAVAILABLE.withDescription("Server shutdownNow invoked");
    boolean savedServerShutdownCallbackInvoked;
    synchronized (lock) {
      // Short-circuiting not strictly necessary, but prevents transports from needing to handle
      // multiple shutdownNow invocations if shutdownNow is called multiple times.
      if (shutdownNowStatus != null) {
        return this;
      }
      shutdownNowStatus = nowStatus;
      transportsCopy = new ArrayList<ServerTransport>(transports);
      savedServerShutdownCallbackInvoked = serverShutdownCallbackInvoked;
    }
    // Short-circuiting not strictly necessary, but prevents transports from needing to handle
    // multiple shutdownNow invocations, between here and the serverShutdown callback.
    if (savedServerShutdownCallbackInvoked) {
      // Have to call shutdownNow, because serverShutdown callback only called shutdown, not
      // shutdownNow
      for (ServerTransport transport : transportsCopy) {
        transport.shutdownNow(nowStatus);
      }
    }
    return this;
  }

  @Override
  public boolean isShutdown() {
    synchronized (lock) {
      return shutdown;
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    synchronized (lock) {
      long timeoutNanos = unit.toNanos(timeout);
      long endTimeNanos = System.nanoTime() + timeoutNanos;
      while (!terminated && (timeoutNanos = endTimeNanos - System.nanoTime()) > 0) {
        NANOSECONDS.timedWait(lock, timeoutNanos);
      }
      return terminated;
    }
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    synchronized (lock) {
      while (!terminated) {
        lock.wait();
      }
    }
  }

  @Override
  public boolean isTerminated() {
    synchronized (lock) {
      return terminated;
    }
  }

  /**
   * Remove transport service from accounting collection and notify of complete shutdown if
   * necessary.
   *
   * @param transport service to remove
   */
  private void transportClosed(ServerTransport transport) {
    synchronized (lock) {
      if (!transports.remove(transport)) {
        throw new AssertionError("Transport already removed");
      }
      checkForTermination();
    }
  }

  /** Notify of complete shutdown if necessary. */
  private void checkForTermination() {
    synchronized (lock) {
      if (shutdown && transports.isEmpty() && transportServerTerminated) {
        if (terminated) {
          throw new AssertionError("Server already terminated");
        }
        terminated = true;
        // TODO(carl-mastrangelo): move this outside the synchronized block.
        lock.notifyAll();
      }
    }
  }

  private class ServerListenerImpl implements ServerListener {
    @Override
    public ServerTransportListener transportCreated(ServerTransport transport) {
      synchronized (lock) {
        transports.add(transport);
      }
      return new ServerTransportListenerImpl(transport);
    }

    @Override
    public void serverShutdown() {
      ArrayList<ServerTransport> copiedTransports;
      Status shutdownNowStatusCopy;
      synchronized (lock) {
        // transports collection can be modified during shutdown(), even if we hold the lock, due
        // to reentrancy.
        copiedTransports = new ArrayList<ServerTransport>(transports);
        shutdownNowStatusCopy = shutdownNowStatus;
        serverShutdownCallbackInvoked = true;
      }
      for (ServerTransport transport : copiedTransports) {
        if (shutdownNowStatusCopy == null) {
          transport.shutdown();
        } else {
          transport.shutdownNow(shutdownNowStatusCopy);
        }
      }
      synchronized (lock) {
        transportServerTerminated = true;
        checkForTermination();
      }
    }
  }

  private class ServerTransportListenerImpl implements ServerTransportListener {
    private final ServerTransport transport;

    public ServerTransportListenerImpl(ServerTransport transport) {
      this.transport = transport;
    }

    @Override
    public void transportTerminated() {
      transportClosed(transport);
    }

    @Override
    public void streamCreated(
        final ServerStream stream, final String methodName, final Metadata headers) {
      final Context.CancellableContext context = createContext(stream, headers);
      final Executor wrappedExecutor;
      // This is a performance optimization that avoids the synchronization and queuing overhead
      // that comes with SerializingExecutor.
      if (executor == directExecutor()) {
        wrappedExecutor = new SerializeReentrantCallsDirectExecutor();
      } else {
        wrappedExecutor = new SerializingExecutor(executor);
      }

      final JumpToApplicationThreadServerStreamListener jumpListener
          = new JumpToApplicationThreadServerStreamListener(wrappedExecutor, stream, context);
      stream.setListener(jumpListener);
      // Run in wrappedExecutor so jumpListener.setListener() is called before any callbacks
      // are delivered, including any errors. Callbacks can still be triggered, but they will be
      // queued.
      wrappedExecutor.execute(new ContextRunnable(context) {
          @Override
          public void runInContext() {
            ServerStreamListener listener = NOOP_LISTENER;
            try {
              ServerMethodDefinition<?, ?> method = registry.lookupMethod(methodName);
              if (method == null) {
                method = fallbackRegistry.lookupMethod(methodName);
              }
              if (method == null) {
                stream.close(
                    Status.UNIMPLEMENTED.withDescription("Method not found: " + methodName),
                    new Metadata());
                context.cancel(null);
                return;
              }
              listener = startCall(stream, methodName, method, headers, context);
            } catch (RuntimeException e) {
              stream.close(Status.fromThrowable(e), new Metadata());
              context.cancel(null);
              throw e;
            } catch (Error e) {
              stream.close(Status.fromThrowable(e), new Metadata());
              context.cancel(null);
              throw e;
            } finally {
              jumpListener.setListener(listener);
            }
          }
        });
    }

    private Context.CancellableContext createContext(final ServerStream stream, Metadata headers) {
      Long timeoutNanos = headers.get(TIMEOUT_KEY);

      if (timeoutNanos == null) {
        return rootContext.withCancellation();
      }

      Context.CancellableContext context =
          rootContext.withDeadlineAfter(timeoutNanos, NANOSECONDS, timeoutService);
      context.addListener(new Context.CancellationListener() {
        @Override
        public void cancelled(Context context) {
          Status status = statusFromCancelled(context);
          if (DEADLINE_EXCEEDED.getCode().equals(status.getCode())) {
            // This should rarely get run, since the client will likely cancel the stream before
            // the timeout is reached.
            stream.cancel(status);
          }
        }
      }, directExecutor());

      return context;
    }

    /** Never returns {@code null}. */
    private <ReqT, RespT> ServerStreamListener startCall(ServerStream stream, String fullMethodName,
        ServerMethodDefinition<ReqT, RespT> methodDef, Metadata headers,
        Context.CancellableContext context) {
      // TODO(ejona86): should we update fullMethodName to have the canonical path of the method?
      ServerCallImpl<ReqT, RespT> call = new ServerCallImpl<ReqT, RespT>(
          stream, methodDef.getMethodDescriptor(), headers, context, decompressorRegistry,
          compressorRegistry);
      ServerCall.Listener<ReqT> listener =
          methodDef.getServerCallHandler().startCall(call, headers);
      if (listener == null) {
        throw new NullPointerException(
            "startCall() returned a null listener for method " + fullMethodName);
      }
      return call.newServerStreamListener(listener);
    }
  }

  private static class NoopListener implements ServerStreamListener {
    @Override
    public void messageRead(InputStream value) {
      try {
        value.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void halfClosed() {}

    @Override
    public void closed(Status status) {}

    @Override
    public void onReady() {}
  }

  /**
   * Dispatches callbacks onto an application-provided executor and correctly propagates
   * exceptions.
   */
  private static class JumpToApplicationThreadServerStreamListener implements ServerStreamListener {
    private final Executor callExecutor;
    private final Context.CancellableContext context;
    private final ServerStream stream;
    // Only accessed from callExecutor.
    private ServerStreamListener listener;

    public JumpToApplicationThreadServerStreamListener(Executor executor,
        ServerStream stream, Context.CancellableContext context) {
      this.callExecutor = executor;
      this.stream = stream;
      this.context = context;
    }

    private ServerStreamListener getListener() {
      if (listener == null) {
        throw new IllegalStateException("listener unset");
      }
      return listener;
    }

    private void setListener(ServerStreamListener listener) {
      Preconditions.checkNotNull(listener, "listener must not be null");
      Preconditions.checkState(this.listener == null, "Listener already set");
      this.listener = listener;
    }

    /**
     * Like {@link ServerCall#close(Status, Metadata)}, but thread-safe for internal use.
     */
    private void internalClose(Status status, Metadata trailers) {
      // TODO(ejona86): this is not thread-safe :)
      stream.close(status, trailers);
    }

    @Override
    public void messageRead(final InputStream message) {
      callExecutor.execute(new ContextRunnable(context) {
        @Override
        public void runInContext() {
          try {
            getListener().messageRead(message);
          } catch (RuntimeException e) {
            internalClose(Status.fromThrowable(e), new Metadata());
            throw e;
          } catch (Error e) {
            internalClose(Status.fromThrowable(e), new Metadata());
            throw e;
          }
        }
      });
    }

    @Override
    public void halfClosed() {
      callExecutor.execute(new ContextRunnable(context) {
        @Override
        public void runInContext() {
          try {
            getListener().halfClosed();
          } catch (RuntimeException e) {
            internalClose(Status.fromThrowable(e), new Metadata());
            throw e;
          } catch (Error e) {
            internalClose(Status.fromThrowable(e), new Metadata());
            throw e;
          }
        }
      });
    }

    @Override
    public void closed(final Status status) {
      callExecutor.execute(new ContextRunnable(context) {
        @Override
        public void runInContext() {
          try {
            getListener().closed(status);
          } finally {
            // Regardless of the status code we cancel the context so that listeners
            // are aware that the call is done.
            context.cancel(status.getCause());
          }
        }
      });
    }

    @Override
    public void onReady() {
      callExecutor.execute(new ContextRunnable(context) {
        @Override
        public void runInContext() {
          getListener().onReady();
        }
      });
    }
  }
}
