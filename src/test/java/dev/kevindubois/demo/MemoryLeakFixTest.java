package dev.kevindubois.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLeakFixTest {

    @Test
    void memoryLeakModeDoesNotGrowLatencyOrLeakMemory() {
        DemoScenarioService service = new DemoScenarioService();
        service.scenarioMode = "memory-leak";
        service.memoryLeakEnabled = true;
        service.init();

        for (int i = 0; i < 200; i++) {
            DemoScenarioService.ScenarioResult result = service.processRequest();
            assertTrue(result.getLatencyMs() <= 50,
                    "Expected baseline latency (<= 50ms) on request " + i + ", got " + result.getLatencyMs() + "ms");
        }
    }
}
