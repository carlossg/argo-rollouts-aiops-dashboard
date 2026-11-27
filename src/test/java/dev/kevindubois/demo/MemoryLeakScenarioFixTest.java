package dev.kevindubois.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLeakScenarioFixTest {

    @Test
    void processRequestDoesNotLeakMemoryOrDegradeLatency() {
        DemoScenarioService service = new DemoScenarioService();
        service.scenarioMode = "memory-leak";
        service.memoryLeakEnabled = true;
        service.init();

        long startMs = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            DemoScenarioService.ScenarioResult result = service.processRequest();
            assertTrue(result.getLatencyMs() <= 50,
                    "Expected baseline latency (<= 50ms) on request " + i + ", got " + result.getLatencyMs() + "ms");
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertTrue(elapsedMs < 1000,
                "Expected 200 requests to complete quickly without memory-leak sleeps, took " + elapsedMs + "ms");
    }
}
