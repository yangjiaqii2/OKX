package com.example.quant.agent.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "quant_agent_run")
public class AgentRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "agent_version_no", nullable = false)
    private int agentVersionNo = 1;

    @Column(name = "scan_run_id", length = 64)
    private String scanRunId;

    @Column(name = "run_mode", nullable = false, length = 32)
    private String runMode;

    @Column(name = "decision", nullable = false, length = 32)
    private String decision;

    @Column(name = "selected_inst_id", length = 64)
    private String selectedInstId;

    @Column(name = "selected_direction", length = 16)
    private String selectedDirection;

    @Column(name = "llm_model", length = 128)
    private String llmModel;

    @Column(name = "summary_reason", length = 512)
    private String summaryReason;

    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public int getAgentVersionNo() { return agentVersionNo; }
    public void setAgentVersionNo(int agentVersionNo) { this.agentVersionNo = agentVersionNo; }
    public String getScanRunId() { return scanRunId; }
    public void setScanRunId(String scanRunId) { this.scanRunId = scanRunId; }
    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getSelectedInstId() { return selectedInstId; }
    public void setSelectedInstId(String selectedInstId) { this.selectedInstId = selectedInstId; }
    public String getSelectedDirection() { return selectedDirection; }
    public void setSelectedDirection(String selectedDirection) { this.selectedDirection = selectedDirection; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public String getSummaryReason() { return summaryReason; }
    public void setSummaryReason(String summaryReason) { this.summaryReason = summaryReason; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
