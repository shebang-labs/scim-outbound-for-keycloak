/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import io.shebanglabs.scimoutbound.auth.TokenCache;
import io.shebanglabs.scimoutbound.config.HostPolicy;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.config.TargetValidator;
import io.shebanglabs.scimoutbound.events.ModelEventListener;
import io.shebanglabs.scimoutbound.http.ScimHttp;
import io.shebanglabs.scimoutbound.metrics.MicrometerScimMetrics;
import io.shebanglabs.scimoutbound.metrics.ScimMetrics;
import io.shebanglabs.scimoutbound.outbox.Backoff;
import io.shebanglabs.scimoutbound.reconcile.ReconcileReport;
import io.shebanglabs.scimoutbound.reconcile.Reconciler;
import io.shebanglabs.scimoutbound.worker.CapabilitiesCache;
import io.shebanglabs.scimoutbound.worker.ClientFactory;
import io.shebanglabs.scimoutbound.worker.OutboxWorker;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.apache.http.impl.client.CloseableHttpClient;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.services.scheduled.ClusterAwareScheduledTaskRunner;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.timer.TimerProvider;

/**
 * Built-in implementation of the {@code scim-outbound} SPI. Owns the server-wide state: operator
 * configuration, HTTP plumbing, caches, and the background worker.
 */
public class DefaultScimOutboundProviderFactory implements ScimOutboundProviderFactory {

    public static final String PROVIDER_ID = "default";
    static final String WORKER_TASK = "scim-outbound-worker";
    static final String RECONCILE_TASK = "scim-outbound-reconcile";

    private static final Logger LOG = Logger.getLogger(DefaultScimOutboundProviderFactory.class);

    private final Clock clock = Clock.systemUTC();
    private final AtomicBoolean draining = new AtomicBoolean();
    private ServerConfig serverConfig = ServerConfig.defaults();
    private HostPolicy hostPolicy;
    private TokenCache tokenCache;
    private CapabilitiesCache capabilities;
    private volatile ClientFactory clientFactory;
    private volatile OutboxWorker worker;
    private ExecutorService executor;
    private ExecutorService reconcileExecutor;
    private volatile Reconciler reconciler;
    private KeycloakSessionFactory sessionFactory;
    private ScimMetrics metrics = ScimMetrics.NOOP;

    @Override
    public ScimOutboundProvider create(KeycloakSession session) {
        return new DefaultScimOutboundProvider(session, this);
    }

    @Override
    public void init(Config.Scope config) {
        serverConfig = ServerConfig.fromLookup(config::get);
        hostPolicy = new HostPolicy(serverConfig);
        tokenCache = new TokenCache(clock);
        capabilities = new CapabilitiesCache(clock);
        metrics = MicrometerScimMetrics.createIfAvailable();
        LOG.infof("scim-outbound: worker every %ds, batch %d, reconcile every %d min, https-only=%s,"
                        + " private-addresses=%s, vault-only-secrets=%s, host allowlist=%s",
                serverConfig.workerInterval().toSeconds(), serverConfig.batchSize(),
                serverConfig.reconcileInterval().toMinutes(), !serverConfig.allowHttp(),
                serverConfig.allowPrivateAddresses(), !serverConfig.allowPlaintextSecrets(),
                serverConfig.allowedHosts().isEmpty() ? "(any public host)" : serverConfig.allowedHosts());
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        this.sessionFactory = factory;
        this.executor = Executors.newSingleThreadExecutor(r -> daemon(r, "scim-outbound-worker"));
        this.reconcileExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "scim-outbound-reconcile"));
        factory.register(new ModelEventListener());
        // schedule only after Keycloak (and our Liquibase changelog) finished migrating the database
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                scheduleBackgroundTasks(factory);
            }
        });
    }

    private void scheduleBackgroundTasks(KeycloakSessionFactory factory) {
        long interval = serverConfig.workerInterval().toMillis();
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            TimerProvider timer = session.getProvider(TimerProvider.class);
            ScheduledTask trigger = new ScheduledTask() {
                @Override
                public void run(KeycloakSession s) {
                    triggerDrain();
                }

                @Override
                public String getTaskName() {
                    return WORKER_TASK;
                }
            };
            timer.schedule(new ClusterAwareScheduledTaskRunner(factory, trigger, interval), interval, WORKER_TASK);
            long reconcileInterval = serverConfig.reconcileInterval().toMillis();
            if (reconcileInterval > 0) {
                ScheduledTask reconcile = new ScheduledTask() {
                    @Override
                    public void run(KeycloakSession s) {
                        reconcileExecutor.execute(() -> reconciler().runScheduled());
                    }

                    @Override
                    public String getTaskName() {
                        return RECONCILE_TASK;
                    }
                };
                timer.schedule(new ClusterAwareScheduledTaskRunner(factory, reconcile, reconcileInterval),
                        reconcileInterval, RECONCILE_TASK);
            }
        });
        LOG.debug("scim-outbound background tasks scheduled");
    }

    /**
     * Starts a drain on the worker thread unless one is already running. The timer thread only
     * hands work over, so slow targets never delay Keycloak's own scheduled tasks.
     */
    public void triggerDrain() {
        if (executor == null || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    Duration budget = serverConfig.workerInterval().multipliedBy(12);
                    worker().drain(budget);
                } catch (RuntimeException e) {
                    LOG.warnf("scim-outbound worker round failed: %s", e.getClass().getSimpleName());
                    LOG.debug("worker failure", e);
                } finally {
                    draining.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            draining.set(false);
        }
    }

    /** Runs a reconciliation of one target in the background. */
    public CompletableFuture<ReconcileReport> triggerReconcile(String targetId, boolean dryRun) {
        return CompletableFuture.supplyAsync(() -> {
            ReconcileReport report = reconciler().run(targetId, dryRun, true);
            triggerDrain();
            return report;
        }, reconcileExecutor);
    }

    Reconciler reconciler() {
        Reconciler r = reconciler;
        if (r == null) {
            synchronized (this) {
                if (reconciler == null) {
                    reconciler = new Reconciler(sessionFactory, serverConfig, clock, clientFactory(), metrics);
                }
                r = reconciler;
            }
        }
        return r;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    OutboxWorker worker() {
        OutboxWorker w = worker;
        if (w == null) {
            synchronized (this) {
                if (worker == null) {
                    worker = new OutboxWorker(sessionFactory, serverConfig, clock,
                            new Backoff(RandomGenerator.getDefault()), clientFactory(), capabilities, metrics);
                }
                w = worker;
            }
        }
        return w;
    }

    /** Lazily built, because Keycloak's HTTP client provider is only usable once the server is up. */
    public ClientFactory clientFactory() {
        ClientFactory f = clientFactory;
        if (f == null) {
            synchronized (this) {
                if (clientFactory == null) {
                    CloseableHttpClient httpClient = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory,
                            s -> s.getProvider(HttpClientProvider.class).getHttpClient());
                    ScimHttp http = new ScimHttp(httpClient, serverConfig.connectTimeout(),
                            serverConfig.readTimeout(), serverConfig.maxResponseBytes(), clock);
                    clientFactory = new ClientFactory(hostPolicy, http, tokenCache);
                }
                f = clientFactory;
            }
        }
        return f;
    }

    /** Forgets cached tokens and capabilities of a target after its configuration changed. */
    public void targetChanged(String targetId) {
        if (clientFactory != null) {
            clientFactory.evict(targetId);
        }
        capabilities.evict(targetId);
    }

    public ServerConfig serverConfig() {
        return serverConfig;
    }

    public Clock clock() {
        return clock;
    }

    public HostPolicy hostPolicy() {
        return hostPolicy;
    }

    public TargetValidator validator() {
        return new TargetValidator(hostPolicy, serverConfig);
    }

    public CapabilitiesCache capabilities() {
        return capabilities;
    }

    public KeycloakSessionFactory sessionFactory() {
        return sessionFactory;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property().name(ServerConfig.ALLOWED_HOSTS).type("string")
                .helpText("Comma-separated host allowlist for SCIM and token URLs (exact names or *.suffix)."
                        + " Empty allows any public host.").add()
                .property().name(ServerConfig.ALLOW_HTTP).type("boolean").defaultValue(false)
                .helpText("Allow plain http:// targets. For local testing only.").add()
                .property().name(ServerConfig.ALLOW_PRIVATE_ADDRESSES).type("boolean").defaultValue(false)
                .helpText("Allow targets that resolve to loopback, private or link-local addresses.").add()
                .property().name(ServerConfig.ALLOW_PLAINTEXT_SECRETS).type("boolean").defaultValue(false)
                .helpText("Accept target secrets that are not ${vault.*} references.").add()
                .property().name(ServerConfig.WORKER_INTERVAL_SECONDS).type("int").defaultValue(5)
                .helpText("How often the outbox worker runs.").add()
                .property().name(ServerConfig.BATCH_SIZE).type("int").defaultValue(50)
                .helpText("Maximum outbox rows claimed per worker round.").add()
                .property().name(ServerConfig.RECONCILE_INTERVAL_MINUTES).type("int").defaultValue(1440)
                .helpText("How often scheduled reconciliation runs; 0 disables it.").add()
                .property().name(ServerConfig.CONNECT_TIMEOUT_MS).type("int").defaultValue(5000)
                .helpText("Connect timeout for SCIM requests.").add()
                .property().name(ServerConfig.READ_TIMEOUT_MS).type("int").defaultValue(30000)
                .helpText("Read timeout for SCIM requests.").add()
                .property().name(ServerConfig.MAX_RESPONSE_BYTES).type("int").defaultValue(5242880)
                .helpText("Maximum accepted SCIM response size.").add()
                .property().name(ServerConfig.MAX_RETRY_HOURS).type("int").defaultValue(24)
                .helpText("A change still failing after this many hours is marked FAILED.").add()
                .property().name(ServerConfig.CLAIM_TIMEOUT_SECONDS).type("int").defaultValue(300)
                .helpText("Claimed rows older than this are released (node crash recovery).").add()
                .property().name(ServerConfig.RETENTION_DAYS).type("int").defaultValue(7)
                .helpText("Days to keep delivered outbox rows.").add()
                .property().name(ServerConfig.FAILED_RETENTION_DAYS).type("int").defaultValue(30)
                .helpText("Days to keep failed outbox rows.").add()
                .build();
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (reconcileExecutor != null) {
            reconcileExecutor.shutdownNow();
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
