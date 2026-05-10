package dev.victormartin.aidatagateway.back.readiness;

import java.util.Map;

public record ReadinessSnapshot(String overall, Map<String, String> components) {}
