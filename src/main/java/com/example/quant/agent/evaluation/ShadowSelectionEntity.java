package com.example.quant.agent.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "shadow_selection")
public class ShadowSelectionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "scan_id", nullable = false, length = 64)
    private String scanId;
    @Column(name = "policy", nullable = false, length = 32)
    private String policy;
    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;
    @Column(name = "eligible_count", nullable = false)
    private int eligibleCount;
    @Column(name = "selected_inst_id", length = 64)
    private String selectedInstId;
    @Column(name = "selected", nullable = false)
    private boolean selected;
    @Column(name = "reason_code", length = 128)
    private String reasonCode;
    @Column(name = "signal_venue", nullable = false, length = 32)
    private String signalVenue;
    @Column(name = "execution_venue", nullable = false, length = 32)
    private String executionVenue;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getPolicy() { return policy; }
    public String getScanId() { return scanId; }
    public String getSignalVenue() { return signalVenue; }
    public String getExecutionVenue() { return executionVenue; }
    public boolean isSelected() { return selected; }
    public int getEligibleCount() { return eligibleCount; }
    public String getSelectedInstId() { return selectedInstId; }
    public void setUserName(String value) { userName = value; }
    public void setScanId(String value) { scanId = value; }
    public void setPolicy(String value) { policy = value; }
    public void setCandidateCount(int value) { candidateCount = value; }
    public void setEligibleCount(int value) { eligibleCount = value; }
    public void setSelectedInstId(String value) { selectedInstId = value; }
    public void setSelected(boolean value) { selected = value; }
    public void setReasonCode(String value) { reasonCode = value; }
    public void setSignalVenue(String value) { signalVenue = value; }
    public void setExecutionVenue(String value) { executionVenue = value; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
