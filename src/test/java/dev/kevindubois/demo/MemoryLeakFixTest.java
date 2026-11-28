package dev.kevindubois.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that DemoScenarioService does not leak memory or add memory-pressure
 * latency degradation when memory-leak mode is configured.
 */
class MemoryLeakFixTest {

    @Test
    void processRequestDoesNotDegradeLatencyWhenMemoryLeakEnabled() {
        DemoScenarioService service = new DemoScenarioService();
        service.scenarioMode = "memory-leak";
        service.memoryLeakEnabled = true;
        service.init();

        long wallStart = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            DemoScenarioService.ScenarioResult result = service.processRequest();
            assertTrue(result.getLatencyMs() <= 50,
                    "Expected baseline latency (<= 50ms) on request " + i + ", got " + result.getLatencyMs() + "ms");
        }
        long elapsedMs = System.currentTimeMillis() - wallStart;
        assertTrue(elapsedMs < 2000,
                "Expected 200 requests to finish without memory-pressure sleeps, took " + elapsedMs + "ms");
    }
}
