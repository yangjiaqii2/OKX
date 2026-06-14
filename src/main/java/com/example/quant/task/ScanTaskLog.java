package com.example.quant.task;

import java.time.Instant;

public record ScanTaskLog(String taskName, String status, String message, Instant createdAt) {
}
