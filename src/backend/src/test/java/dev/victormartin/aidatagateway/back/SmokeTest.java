package dev.victormartin.aidatagateway.back;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SmokeTest {

    @Test
    void mainClassExists() {
        assertNotNull(BackApplication.class);
    }
}
