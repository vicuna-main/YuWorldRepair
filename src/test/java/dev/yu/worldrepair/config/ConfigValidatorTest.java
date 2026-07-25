package dev.yu.worldrepair.config;

import dev.yu.worldrepair.guard.GuardMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigValidatorTest {
    @Test
    void invalidValuesFallBackWithoutThrowing() {
        List<String> warnings = new ArrayList<>();
        RuntimeConfig result = ConfigValidator.validate(
                Map.of(
                        "mode", "\"destructive\"",
                        "maxSignatures", "-1",
                        "windowSeconds", "not-a-number",
                        "enableNegativeCache", "maybe"
                ),
                warnings::add
        );

        assertEquals(GuardMode.OBSERVE, result.mode());
        assertEquals(1_024, result.maxSignatures());
        assertEquals(60_000_000_000L, result.windowNanos());
        assertFalse(result.enableNegativeCache());
        assertEquals(4, warnings.size());
    }

    @Test
    void parserRespectsQuotedCommentCharacters() {
        Map<String, String> values = YuWorldRepairConfig.parse("""
                mode = "guard" # comment
                note = "value#inside"
                maxSignatures = 2048
                """);

        assertEquals("\"guard\"", values.get("mode"));
        assertEquals("\"value#inside\"", values.get("note"));
        assertEquals("2048", values.get("maxSignatures"));
    }
}
