package com.example.quant.notification;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_outbox", uniqueConstraints =
        @UniqueConstraint(name = "uk_notification_event_channel", columnNames = {"trade_event_id", "channel"}))
public class NotificationOutboxEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "trade_event_id", nullable = false) private Long tradeEventId;
    @Column(nullable = false, length = 32) private String channel;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "last_http_status") private Integer lastHttpStatus;
    @Column(name = "last_error", length = 512) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public Long getId() { return id; }
    public Long getTradeEventId() { return tradeEventId; }
    public void setTradeEventId(Long tradeEventId) { this.tradeEventId = tradeEventId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public void setLastHttpStatus(Integer lastHttpStatus) { this.lastHttpStatus = lastHttpStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
