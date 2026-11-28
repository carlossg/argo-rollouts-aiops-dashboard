package dev.kevindubois.demo;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that simulates various deployment scenarios and bugs for progressive delivery demonstrations.
 * This service is designed to showcase how Argo Rollouts can detect and respond to different types of issues.
 */
@ApplicationScoped
public class DemoScenarioService {

    private static final Logger LOG = Logger.getLogger(DemoScenarioService.class);

    @ConfigProperty(name = "scenario.mode", defaultValue = "happy")
    String scenarioMode;

    @ConfigProperty(name = "enable.memory.leak", defaultValue = "false")
    boolean memoryLeakEnabled;

    @ConfigProperty(name = "enable.connection.leak", defaultValue = "false")
    boolean connectionLeakEnabled;

    @ConfigProperty(name = "enable.cpu.spike", defaultValue = "false")
    boolean cpuSpikeEnabled;

    @ConfigProperty(name = "enable.latency.drift", defaultValue = "false")
    boolean latencyDriftEnabled;

    // Latency drift simulation
    private long latencyDriftStartTime;

    // Latency drift parameters:
    // - No added delay during the grace period, so an analysis early in the rollout sees baseline latency
    // - After the grace period the delay grows linearly with uptime
    // - The delay is capped so the load generator keeps producing traffic (slow responses, no timeouts)
    static final long LATENCY_DRIFT_GRACE_SECONDS = 20;
    static final long LATENCY_DRIFT_GROWTH_MS_PER_SECOND = 5;
    static final long LATENCY_DRIFT_MAX_DELAY_MS = 250;

    // Connection pool simulation
    private Semaphore connectionPool;
    private static final int MAX_CONNECTIONS = 10;

    // Request counter for CPU spike
    private AtomicLong requestCount = new AtomicLong(0);

    @PostConstruct
    void init() {
        // Initialize connection pool if enabled
        if (connectionLeakEnabled) {
            connectionPool = new Semaphore(MAX_CONNECTIONS);
        }

        // Initialize latency drift timer if enabled
        if (latencyDriftEnabled) {
            latencyDriftStartTime = System.currentTimeMillis();
        }

        LOG.info("DemoScenarioService initialized with mode: " + scenarioMode);
    }

    /**
     * Simulates request processing with various scenarios and bugs.
     * Returns a ScenarioResult containing success status and latency.
     */
    public ScenarioResult processRequest() {
        long startTime = System.currentTimeMillis();
        boolean isSuccess = true;
        long latencyMs = 0;

        try {
            // Increment request counter
            long currentRequest = requestCount.incrementAndGet();

            // Execute bug scenarios first (they can throw exceptions)
            long memoryLeakLatency = executeMemoryLeakScenario(currentRequest);
            executeConnectionLeakScenario();
            executeCpuSpikeScenario(currentRequest);
            long latencyDriftDelay = executeLatencyDriftScenario();

            // Then execute the configured scenario mode
            ScenarioResult result = executeScenarioMode();
            isSuccess = result.isSuccess();
            latencyMs = result.getLatencyMs() + memoryLeakLatency + latencyDriftDelay;

        } catch (Exception e) {
            isSuccess = false;
            latencyMs = System.currentTimeMillis() - startTime;
            LOG.error("Request failed due to bug scenario: " + e.getMessage(), e);
        }

        return new ScenarioResult(isSuccess, latencyMs);
    }

    /**
     * Executes the configured scenario mode (happy or failure).
     */
    private ScenarioResult executeScenarioMode() {
        boolean isSuccess;
        long latencyMs;

        if ("failure".equals(scenarioMode)) {
            // Failure scenario: 70% success rate, high latency
            isSuccess = Math.random() > 0.3;
            latencyMs = (long) (100 + Math.random() * 400);

            if (!isSuccess) {
                LOG.error("CRITICAL ERROR: Request failed with database connection timeout after " + latencyMs + "ms",
                    new RuntimeException("Database connection pool exhausted - unable to acquire connection"));
            } else if (latencyMs > 300) {
                LOG.error("PERFORMANCE DEGRADATION: High latency detected: " + latencyMs + "ms - service is struggling");
            }
        } else {
            // Happy scenario: 99% success rate, low latency
            isSuccess = Math.random() > 0.01;
            latencyMs = (long) (10 + Math.random() * 40);

            if (!isSuccess) {
                LOG.warn("Occasional request failure: " + latencyMs + "ms");
            }
        }

        return new ScenarioResult(isSuccess, latencyMs);
    }

    /**
     * Memory leak scenario previously retained 1MB buffers per request without cleanup
     * and added artificial latency. Buffers are no longer retained so heap usage and
     * response latency remain bounded.
     *
     * @return Additional latency in milliseconds (0 when no leak occurs)
     */
    private long executeMemoryLeakScenario(long requestNumber) {
        return 0;
    }

    /**
     * BUG SCENARIO 2: Connection Pool Exhaustion
     * Simulates a connection leak where connections are not properly released.
     * 20% of connections are leaked, eventually exhausting the pool.
     */
    private void executeConnectionLeakScenario() {
        if (!connectionLeakEnabled) {
            return;
        }

        boolean acquired = false;
        try {
            // Try to acquire a connection with timeout
            acquired = connectionPool.tryAcquire(100, TimeUnit.MILLISECONDS);

            if (!acquired) {
                // Pool exhausted - this is the bug manifesting
                LOG.error("CONNECTION POOL EXHAUSTED: No connections available. " +
                    "All " + MAX_CONNECTIONS + " connections are in use. Request will fail.");
                throw new RuntimeException("Connection pool exhausted - unable to acquire connection within timeout");
            }

            // Simulate connection usage
            Thread.sleep(5);

            // BUG: 20% of the time, we "forget" to release the connection
            if (Math.random() > 0.2) {
                connectionPool.release();
            } else {
                // Connection leaked!
                int available = connectionPool.availablePermits();
                if (available < 5) {
                    LOG.warn("CONNECTION LEAK: Connection not released. " +
                        "Available connections: " + available + "/" + MAX_CONNECTIONS);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Connection acquisition interrupted", e);
            throw new RuntimeException("Connection acquisition interrupted", e);
        }
    }

    /**
     * BUG SCENARIO 3: CPU Spike
     * Every 10th request triggers expensive computation, causing CPU spikes.
     * This simulates inefficient code that causes performance degradation.
     */
    private void executeCpuSpikeScenario(long requestNumber) {
        if (!cpuSpikeEnabled) {
            return;
        }

        // Every 10th request triggers CPU spike
        if (requestNumber % 10 == 0) {
            LOG.warn("CPU SPIKE TRIGGERED: Executing expensive computation on request #" + requestNumber);

            long startTime = System.nanoTime();

            // Expensive computation - calculate square roots in a tight loop
            double result = 0;
            for (int i = 0; i < 10_000_000; i++) {
                result += Math.sqrt(i);
            }

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            LOG.error("CPU SPIKE COMPLETED: Expensive computation took " + durationMs + "ms. " +
                "This causes thread starvation and latency spikes. Result: " + result);
        }
    }

    /**
     * BUG SCENARIO 4: Latency Drift (metrics-only regression)
     * Adds an artificial per-request delay that grows with pod uptime:
     * no delay for the first {@link #LATENCY_DRIFT_GRACE_SECONDS} seconds, then a linear
     * ramp of {@link #LATENCY_DRIFT_GROWTH_MS_PER_SECOND} ms per second, capped at
     * {@link #LATENCY_DRIFT_MAX_DELAY_MS} ms.
     *
     * Key characteristics for the v4.latency scenario:
     * - An analysis early in the rollout sees baseline latency and passes
     * - After 30-60 seconds of load the p95 latency clearly exceeds 1.5x the stable baseline
     * - No errors, no exceptions, no log output - the regression is only visible in metrics
     * - The cap keeps responses slow but successful, so the load generator keeps running
     *
     * @return Additional latency in milliseconds caused by the drift
     */
    private long executeLatencyDriftScenario() {
        if (!latencyDriftEnabled) {
            return 0;
        }

        long elapsedSeconds = (System.currentTimeMillis() - latencyDriftStartTime) / 1000;
        long delayMs = computeLatencyDriftDelayMs(elapsedSeconds);

        // Simulate the latency. No logging on purpose: this scenario must stay
        // invisible in logs so only metric analysis can catch it.
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return delayMs;
    }

    /**
     * Computes the latency drift delay for a given uptime.
     * Linear ramp after the grace period, capped at the maximum delay.
     */
    static long computeLatencyDriftDelayMs(long elapsedSeconds) {
        if (elapsedSeconds <= LATENCY_DRIFT_GRACE_SECONDS) {
            return 0;
        }
        long delay = (elapsedSeconds - LATENCY_DRIFT_GRACE_SECONDS) * LATENCY_DRIFT_GROWTH_MS_PER_SECOND;
        return Math.min(delay, LATENCY_DRIFT_MAX_DELAY_MS);
    }

    public String getScenarioMode() {
        return scenarioMode;
    }

    public boolean isMemoryLeakEnabled() {
        return memoryLeakEnabled;
    }

    public boolean isConnectionLeakEnabled() {
        return connectionLeakEnabled;
    }

    public boolean isCpuSpikeEnabled() {
        return cpuSpikeEnabled;
    }

    public boolean isLatencyDriftEnabled() {
        return latencyDriftEnabled;
    }

    /**
     * Result of a scenario execution.
     */
    public static class ScenarioResult {
        private final boolean success;
        private final long latencyMs;

        public ScenarioResult(boolean success, long latencyMs) {
            this.success = success;
            this.latencyMs = latencyMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public long getLatencyMs() {
            return latencyMs;
        }
    }
}

