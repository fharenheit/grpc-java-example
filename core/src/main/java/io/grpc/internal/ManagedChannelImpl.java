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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.ResolvedServerInfo;
import io.grpc.Status;
import io.grpc.TransportManager;
import io.grpc.TransportManager.InterimTransport;
import io.grpc.TransportManager.OobTransportProvider;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/** A communication channel for making outgoing RPCs. */
@ThreadSafe
public final class ManagedChannelImpl extends ManagedChannel implements WithLogId {
  private static final Logger log = Logger.getLogger(ManagedChannelImpl.class.getName());

  // Matching this pattern means the target string is a URI target or at least intended to be one.
  // A URI target must be an absolute hierarchical URI.
  // From RFC 2396: scheme = alpha *( alpha | digit | "+" | "-" | "." )
  @VisibleForTesting
  static final Pattern URI_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:/.*");

  static final long IDLE_TIMEOUT_MILLIS_DISABLE = -1;

  private static final ClientTransport SHUTDOWN_TRANSPORT =
      new FailingClientTransport(Status.UNAVAILABLE.withDescription("Channel is shutdown"));

  @VisibleForTesting
  static final ClientTransport IDLE_MODE_TRANSPORT =
      new FailingClientTransport(Status.INTERNAL.withDescription("Channel is in idle mode"));

  private final String target;
  private final NameResolver.Factory nameResolverFactory;
  private final Attributes nameResolverParams;
  private final LoadBalancer.Factory loadBalancerFactory;
  private final ClientTransportFactory transportFactory;
  private final Executor executor;
  private final boolean usingSharedExecutor;
  private final Object lock = new Object();

  private final DecompressorRegistry decompressorRegistry;
  private final CompressorRegistry compressorRegistry;

  private final SharedResourceHolder.Resource<ScheduledExecutorService> timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final long idleTimeoutMillis;

  /**
   * Executor that runs deadline timers for requests.
   */
  private ScheduledExecutorService scheduledExecutor;

  private final BackoffPolicy.Provider backoffPolicyProvider;

  /**
   * We delegate to this channel, so that we can have interceptors as necessary. If there aren't
   * any interceptors this will just be {@link RealChannel}.
   */
  private final Channel interceptorChannel;
  @Nullable private final String userAgent;

  // Never be null. Must be modified under lock.
  private NameResolver nameResolver;

  // null iff channel is in idle state
  @GuardedBy("lock")
  @Nullable
  private LoadBalancer<ClientTransport> loadBalancer;

  /**
   * Maps EquivalentAddressGroups to transports for that server.
   */
  @GuardedBy("lock")
  private final Map<EquivalentAddressGroup, TransportSet> transports =
      new HashMap<EquivalentAddressGroup, TransportSet>();

  /**
   * TransportSets that are shutdown (but not yet terminated) due to channel idleness.
   */
  @GuardedBy("lock")
  private final HashSet<TransportSet> decommissionedTransports = new HashSet<TransportSet>();

  @GuardedBy("lock")
  private final HashSet<DelayedClientTransport> delayedTransports =
      new HashSet<DelayedClientTransport>();

  @VisibleForTesting
  final InUseStateAggregator<Object> inUseStateAggregator =
      new InUseStateAggregator<Object>() {
        @Override
        Object getLock() {
          return lock;
        }

        @Override
        @GuardedBy("lock")
        Runnable handleInUse() {
          return exitIdleMode();
        }

        @GuardedBy("lock")
        @Override
        void handleNotInUse() {
          if (shutdown) {
            return;
          }
          rescheduleIdleTimer();
        }
      };

  private class IdleModeTimer implements Runnable {
    @GuardedBy("lock")
    boolean cancelled;

    @Override
    public void run() {
      ArrayList<TransportSet> transportsCopy = new ArrayList<TransportSet>();
      LoadBalancer<ClientTransport> savedBalancer;
      NameResolver oldResolver;
      synchronized (lock) {
        if (cancelled) {
          // Race detected: this task started before cancelIdleTimer() could cancel it.
          return;
        }
        // Enter idle mode
        savedBalancer = loadBalancer;
        loadBalancer = null;
        oldResolver = nameResolver;
        nameResolver = getNameResolver(target, nameResolverFactory, nameResolverParams);
        transportsCopy.addAll(transports.values());
        transports.clear();
        decommissionedTransports.addAll(transportsCopy);
      }
      for (TransportSet ts : transportsCopy) {
        ts.shutdown();
      }
      savedBalancer.shutdown();
      oldResolver.shutdown();
    }
  }

  @GuardedBy("lock")
  @Nullable
  private ScheduledFuture<?> idleModeTimerFuture;
  @GuardedBy("lock")
  @Nullable
  private IdleModeTimer idleModeTimer;

  /**
   * Make the channel exit idle mode, if it's in it. Return a LoadBalancer that can be used for
   * making new requests. Return null if the channel is shutdown.
   */
  @VisibleForTesting
  LoadBalancer<ClientTransport> exitIdleModeAndGetLb() {
    Runnable runnable;
    LoadBalancer<ClientTransport> balancer;
    synchronized (lock) {
      runnable = exitIdleMode();
      balancer = loadBalancer;
    }
    if (runnable != null) {
      runnable.run();
    }
    return balancer;
  }

  /**
   * Make the channel exit idle mode, if it's in it. If the returned runnable is non-{@code null},
   * then it should be executed by the caller after releasing {@code lock}.
   */
  @GuardedBy("lock")
  private Runnable exitIdleMode() {
    final LoadBalancer<ClientTransport> balancer;
    final NameResolver resolver;
    if (shutdown) {
      return null;
    }
    if (inUseStateAggregator.isInUse()) {
      cancelIdleTimer();
    } else {
      // exitIdleMode() may be called outside of inUseStateAggregator, which may still in
      // "not-in-use" state. If it's the case, we start the timer which will be soon cancelled if
      // the aggregator receives actual uses.
      rescheduleIdleTimer();
    }
    if (loadBalancer != null) {
      return null;
    }
    balancer = loadBalancerFactory.newLoadBalancer(nameResolver.getServiceAuthority(), tm);
    this.loadBalancer = balancer;
    resolver = this.nameResolver;
    class NameResolverStartTask implements Runnable {
      @Override
      public void run() {
        // This may trigger quite a few non-trivial work in LoadBalancer and NameResolver,
        // we don't want to do it in the lock.
        resolver.start(new NameResolverListenerImpl(balancer));
      }
    }

    return new NameResolverStartTask();
  }

  @GuardedBy("lock")
  private void cancelIdleTimer() {
    if (idleModeTimerFuture != null) {
      idleModeTimerFuture.cancel(false);
      idleModeTimer.cancelled = true;
      idleModeTimerFuture = null;
      idleModeTimer = null;
    }
  }

  @GuardedBy("lock")
  private void rescheduleIdleTimer() {
    if (idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      return;
    }
    cancelIdleTimer();
    idleModeTimer = new IdleModeTimer();
    idleModeTimerFuture = scheduledExecutor.schedule(new LogExceptionRunnable(idleModeTimer),
        idleTimeoutMillis, TimeUnit.MILLISECONDS);
  }

  @GuardedBy("lock")
  private final HashSet<OobTransportProviderImpl> oobTransports =
      new HashSet<OobTransportProviderImpl>();

  @GuardedBy("lock")
  private boolean shutdown;
  @GuardedBy("lock")
  private boolean shutdownNowed;
  @GuardedBy("lock")
  private boolean terminated;

  private final ClientTransportProvider transportProvider = new ClientTransportProvider() {
    @Override
    public ClientTransport get(CallOptions callOptions) {
      LoadBalancer<ClientTransport> balancer = exitIdleModeAndGetLb();
      if (balancer == null) {
        return SHUTDOWN_TRANSPORT;
      }
      return balancer.pickTransport(callOptions.getAffinity());
    }
  };

  ManagedChannelImpl(String target, BackoffPolicy.Provider backoffPolicyProvider,
      NameResolver.Factory nameResolverFactory, Attributes nameResolverParams,
      LoadBalancer.Factory loadBalancerFactory, ClientTransportFactory transportFactory,
      DecompressorRegistry decompressorRegistry, CompressorRegistry compressorRegistry,
      SharedResourceHolder.Resource<ScheduledExecutorService> timerService,
      Supplier<Stopwatch> stopwatchSupplier, long idleTimeoutMillis,
      @Nullable Executor executor, @Nullable String userAgent,
      List<ClientInterceptor> interceptors) {
    this.target = checkNotNull(target, "target");
    this.nameResolverFactory = checkNotNull(nameResolverFactory, "nameResolverFactory");
    this.nameResolverParams = checkNotNull(nameResolverParams, "nameResolverParams");
    this.nameResolver = getNameResolver(target, nameResolverFactory, nameResolverParams);
    this.loadBalancerFactory = checkNotNull(loadBalancerFactory, "loadBalancerFactory");
    if (executor == null) {
      usingSharedExecutor = true;
      this.executor = SharedResourceHolder.get(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
    } else {
      usingSharedExecutor = false;
      this.executor = executor;
    }
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.transportFactory =
        new CallCredentialsApplyingTransportFactory(transportFactory, this.executor);
    this.interceptorChannel = ClientInterceptors.intercept(new RealChannel(), interceptors);
    this.timerService = timerService;
    this.scheduledExecutor = SharedResourceHolder.get(timerService);
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    checkArgument(idleTimeoutMillis > 0 || idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE,
        "invalid idleTimeoutMillis %s", idleTimeoutMillis);
    this.idleTimeoutMillis = idleTimeoutMillis;
    this.decompressorRegistry = decompressorRegistry;
    this.compressorRegistry = compressorRegistry;
    this.userAgent = userAgent;

    if (log.isLoggable(Level.INFO)) {
      log.log(Level.INFO, "[{0}] Created with target {1}", new Object[] {getLogId(), target});
    }
  }

  private static boolean serversAreEmpty(List<? extends List<ResolvedServerInfo>> servers) {
    for (List<ResolvedServerInfo> serverInfos : servers) {
      if (!serverInfos.isEmpty()) {
        return false;
      }
    }

    return true;
  }

  @VisibleForTesting
  static NameResolver getNameResolver(String target, NameResolver.Factory nameResolverFactory,
      Attributes nameResolverParams) {
    // Finding a NameResolver. Try using the target string as the URI. If that fails, try prepending
    // "dns:///".
    URI targetUri = null;
    StringBuilder uriSyntaxErrors = new StringBuilder();
    try {
      targetUri = new URI(target);
      // For "localhost:8080" this would likely cause newNameResolver to return null, because
      // "localhost" is parsed as the scheme. Will fall into the next branch and try
      // "dns:///localhost:8080".
    } catch (URISyntaxException e) {
      // Can happen with ip addresses like "[::1]:1234" or 127.0.0.1:1234.
      uriSyntaxErrors.append(e.getMessage());
    }
    if (targetUri != null) {
      NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverParams);
      if (resolver != null) {
        return resolver;
      }
      // "foo.googleapis.com:8080" cause resolver to be null, because "foo.googleapis.com" is an
      // unmapped scheme. Just fall through and will try "dns:///foo.googleapis.com:8080"
    }

    // If we reached here, the targetUri couldn't be used.
    if (!URI_PATTERN.matcher(target).matches()) {
      // It doesn't look like a URI target. Maybe it's an authority string. Try with the default
      // scheme from the factory.
      try {
        targetUri = new URI(nameResolverFactory.getDefaultScheme(), "", "/" + target, null);
      } catch (URISyntaxException e) {
        // Should not be possible.
        throw new IllegalArgumentException(e);
      }
      if (targetUri != null) {
        NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverParams);
        if (resolver != null) {
          return resolver;
        }
      }
    }
    throw new IllegalArgumentException(String.format(
        "cannot find a NameResolver for %s%s",
        target, uriSyntaxErrors.length() > 0 ? " (" + uriSyntaxErrors + ")" : ""));
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled.
   */
  @Override
  public ManagedChannelImpl shutdown() {
    ArrayList<TransportSet> transportsCopy = new ArrayList<TransportSet>();
    ArrayList<DelayedClientTransport> delayedTransportsCopy =
        new ArrayList<DelayedClientTransport>();
    ArrayList<OobTransportProviderImpl> oobTransportsCopy =
        new ArrayList<OobTransportProviderImpl>();
    LoadBalancer<ClientTransport> balancer;
    NameResolver resolver;
    synchronized (lock) {
      if (shutdown) {
        return this;
      }
      shutdown = true;
      // After shutdown there are no new calls, so no new cancellation tasks are needed
      scheduledExecutor = SharedResourceHolder.release(timerService, scheduledExecutor);
      maybeTerminateChannel();
      if (!terminated) {
        transportsCopy.addAll(transports.values());
        delayedTransportsCopy.addAll(delayedTransports);
        oobTransportsCopy.addAll(oobTransports);
      }
      balancer = loadBalancer;
      resolver = nameResolver;
      cancelIdleTimer();
    }
    if (balancer != null) {
      balancer.shutdown();
    }
    resolver.shutdown();
    for (TransportSet ts : transportsCopy) {
      ts.shutdown();
    }
    for (DelayedClientTransport transport : delayedTransportsCopy) {
      transport.shutdown();
    }
    for (OobTransportProviderImpl provider : oobTransportsCopy) {
      provider.close();
    }
    if (log.isLoggable(Level.FINE)) {
      log.log(Level.FINE, "[{0}] Shutting down", getLogId());
    }
    return this;
  }

  /**
   * Initiates a forceful shutdown in which preexisting and new calls are cancelled. Although
   * forceful, the shutdown process is still not instantaneous; {@link #isTerminated()} will likely
   * return {@code false} immediately after this method returns.
   */
  @Override
  public ManagedChannelImpl shutdownNow() {
    synchronized (lock) {
      // Short-circuiting not strictly necessary, but prevents transports from needing to handle
      // multiple shutdownNow invocations.
      if (shutdownNowed) {
        return this;
      }
      shutdownNowed = true;
    }
    shutdown();
    List<TransportSet> transportsCopy;
    List<DelayedClientTransport> delayedTransportsCopy;
    List<OobTransportProviderImpl> oobTransportsCopy;
    synchronized (lock) {
      transportsCopy = new ArrayList<TransportSet>(transports.values());
      delayedTransportsCopy = new ArrayList<DelayedClientTransport>(delayedTransports);
      oobTransportsCopy = new ArrayList<OobTransportProviderImpl>(oobTransports);
    }
    if (log.isLoggable(Level.FINE)) {
      log.log(Level.FINE, "[{0}] Shutting down now", getLogId());
    }
    Status nowStatus = Status.UNAVAILABLE.withDescription("Channel shutdownNow invoked");
    for (TransportSet ts : transportsCopy) {
      ts.shutdownNow(nowStatus);
    }
    for (DelayedClientTransport transport : delayedTransportsCopy) {
      transport.shutdownNow(nowStatus);
    }
    for (OobTransportProviderImpl provider : oobTransportsCopy) {
      provider.shutdownNow(nowStatus);
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
        TimeUnit.NANOSECONDS.timedWait(lock, timeoutNanos);
      }
      return terminated;
    }
  }

  @Override
  public boolean isTerminated() {
    synchronized (lock) {
      return terminated;
    }
  }

  /*
   * Creates a new outgoing call on the channel.
   */
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions) {
    return interceptorChannel.newCall(method, callOptions);
  }

  @Override
  public String authority() {
    return interceptorChannel.authority();
  }

  private class RealChannel extends Channel {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions) {
      Executor executor = callOptions.getExecutor();
      if (executor == null) {
        executor = ManagedChannelImpl.this.executor;
      }
      return new ClientCallImpl<ReqT, RespT>(
          method,
          executor,
          callOptions,
          transportProvider,
          scheduledExecutor)
              .setDecompressorRegistry(decompressorRegistry)
              .setCompressorRegistry(compressorRegistry);
    }

    @Override
    public String authority() {
      String authority = nameResolver.getServiceAuthority();
      return checkNotNull(authority, "authority");
    }
  }

  /**
   * Terminate the channel if termination conditions are met.
   */
  @GuardedBy("lock")
  private void maybeTerminateChannel() {
    if (terminated) {
      return;
    }
    if (shutdown && transports.isEmpty() && decommissionedTransports.isEmpty()
        && delayedTransports.isEmpty() && oobTransports.isEmpty()) {
      if (log.isLoggable(Level.INFO)) {
        log.log(Level.INFO, "[{0}] Terminated", getLogId());
      }
      terminated = true;
      lock.notifyAll();
      if (usingSharedExecutor) {
        SharedResourceHolder.release(GrpcUtil.SHARED_CHANNEL_EXECUTOR, (ExecutorService) executor);
      }
      // Release the transport factory so that it can deallocate any resources.
      transportFactory.close();
    }
  }

  @VisibleForTesting
  final TransportManager<ClientTransport> tm = new TransportManager<ClientTransport>() {
    @Override
    public void updateRetainedTransports(Collection<EquivalentAddressGroup> addrs) {
      // TODO(zhangkun83): warm-up new servers and discard removed servers.
    }

    @Override
    public ClientTransport getTransport(final EquivalentAddressGroup addressGroup) {
      checkNotNull(addressGroup, "addressGroup");
      TransportSet ts;
      synchronized (lock) {
        if (shutdown) {
          return SHUTDOWN_TRANSPORT;
        }
        if (loadBalancer == null) {
          return IDLE_MODE_TRANSPORT;
        }
        ts = transports.get(addressGroup);
        if (ts == null) {
          ts = new TransportSet(addressGroup, authority(), userAgent, loadBalancer,
              backoffPolicyProvider, transportFactory, scheduledExecutor, stopwatchSupplier,
              executor, new TransportSet.Callback() {
                @Override
                public void onTerminated(TransportSet ts) {
                  synchronized (lock) {
                    transports.remove(addressGroup);
                    decommissionedTransports.remove(ts);
                    maybeTerminateChannel();
                  }
                }

                @Override
                public void onAllAddressesFailed() {
                  nameResolver.refresh();
                }

                @Override
                public void onConnectionClosedByServer(Status status) {
                  nameResolver.refresh();
                }

                @Override
                public Runnable onInUse(TransportSet ts) {
                  return inUseStateAggregator.updateObjectInUse(ts, true);
                }

                @Override
                public void onNotInUse(TransportSet ts) {
                  Runnable r = inUseStateAggregator.updateObjectInUse(ts, false);
                  assert r == null;
                }
              });
          if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "[{0}] {1} created for {2}",
                new Object[] {getLogId(), ts.getLogId(), addressGroup});
          }
          transports.put(addressGroup, ts);
        }
      }
      return ts.obtainActiveTransport();
    }

    @Override
    public Channel makeChannel(ClientTransport transport) {
      return new SingleTransportChannel(
          transport, executor, scheduledExecutor, authority());
    }

    @Override
    public ClientTransport createFailingTransport(Status error) {
      return new FailingClientTransport(error);
    }

    @Override
    public InterimTransport<ClientTransport> createInterimTransport() {
      return new InterimTransportImpl();
    }

    @Override
    public OobTransportProvider<ClientTransport> createOobTransportProvider(
        EquivalentAddressGroup addressGroup, String authority) {
      return new OobTransportProviderImpl(addressGroup, authority);
    }
  };

  @Override
  public String getLogId() {
    return GrpcUtil.getLogId(this);
  }

  private class NameResolverListenerImpl implements NameResolver.Listener {
    final LoadBalancer<ClientTransport> balancer;

    NameResolverListenerImpl(LoadBalancer<ClientTransport> balancer) {
      this.balancer = balancer;
    }

    @Override
    public void onUpdate(List<? extends List<ResolvedServerInfo>> servers, Attributes config) {
      if (serversAreEmpty(servers)) {
        onError(Status.UNAVAILABLE.withDescription("NameResolver returned an empty list"));
      } else {
        try {
          balancer.handleResolvedAddresses(servers, config);
        } catch (Throwable e) {
          // It must be a bug! Push the exception back to LoadBalancer in the hope that it may be
          // propagated to the application.
          balancer.handleNameResolutionError(Status.INTERNAL.withCause(e)
              .withDescription("Thrown from handleResolvedAddresses(): " + e));
        }
      }
    }

    @Override
    public void onError(Status error) {
      checkArgument(!error.isOk(), "the error status must not be OK");
      balancer.handleNameResolutionError(error);
    }
  }

  private class InterimTransportImpl implements InterimTransport<ClientTransport> {
    private final DelayedClientTransport delayedTransport;
    private boolean closed;

    InterimTransportImpl() {
      delayedTransport = new DelayedClientTransport(executor);
      delayedTransport.start(new ManagedClientTransport.Listener() {
          @Override public void transportShutdown(Status status) {}

          @Override public void transportTerminated() {
            synchronized (lock) {
              delayedTransports.remove(delayedTransport);
              maybeTerminateChannel();
            }
            Runnable r = inUseStateAggregator.updateObjectInUse(delayedTransport, false);
            assert r == null;
          }

          @Override public void transportReady() {}

          @Override public void transportInUse(boolean inUse) {
            Runnable r = inUseStateAggregator.updateObjectInUse(delayedTransport, inUse);
            if (r != null) {
              r.run();
            }
          }
        });
      boolean savedShutdown;
      synchronized (lock) {
        delayedTransports.add(delayedTransport);
        savedShutdown = shutdown;
      }
      if (savedShutdown) {
        delayedTransport.setTransport(SHUTDOWN_TRANSPORT);
        delayedTransport.shutdown();
      }
    }

    @Override
    public ClientTransport transport() {
      checkState(!closed, "already closed");
      return delayedTransport;
    }

    @Override
    public void closeWithRealTransports(Supplier<ClientTransport> realTransports) {
      delayedTransport.setTransportSupplier(realTransports);
      delayedTransport.shutdown();
    }

    @Override
    public void closeWithError(Status error) {
      delayedTransport.shutdownNow(error);
    }
  }

  private class OobTransportProviderImpl implements OobTransportProvider<ClientTransport> {
    private final TransportSet transportSet;
    private final ClientTransport transport;

    OobTransportProviderImpl(EquivalentAddressGroup addressGroup, String authority) {
      synchronized (lock) {
        if (shutdown) {
          transportSet = null;
          transport = SHUTDOWN_TRANSPORT;
        } else if (loadBalancer == null) {
          transportSet = null;
          transport = IDLE_MODE_TRANSPORT;
        } else {
          transport = null;
          transportSet = new TransportSet(addressGroup, authority, userAgent, loadBalancer,
              backoffPolicyProvider, transportFactory, scheduledExecutor, stopwatchSupplier,
              executor, new TransportSet.Callback() {
                @Override
                public void onTerminated(TransportSet ts) {
                  synchronized (lock) {
                    oobTransports.remove(OobTransportProviderImpl.this);
                    maybeTerminateChannel();
                  }
                }
              });
          oobTransports.add(this);
        }
      }
    }

    @Override
    public ClientTransport get() {
      if (transport != null) {
        return transport;
      } else {
        return transportSet.obtainActiveTransport();
      }
    }

    @Override
    public void close() {
      if (transportSet != null) {
        transportSet.shutdown();
      }
    }

    void shutdownNow(Status reason) {
      if (transportSet != null) {
        transportSet.shutdownNow(reason);
      }
    }
  }
}
