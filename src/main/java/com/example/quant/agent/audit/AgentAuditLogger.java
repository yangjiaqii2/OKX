package com.example.quant.agent.audit;

import com.example.quant.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(AgentAuditLogger.class);

    private final AgentProperties agentProperties;
    private final AgentAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AgentAuditLogger(AgentProperties agentProperties, AgentAuditLogRepository repository, ObjectMapper objectMapper) {
        this.agentProperties = agentProperties;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void info(String eventType, String entityType, String entityId, String message, Object payload) {
        write("INFO", eventType, entityType, entityId, message, payload);
    }

    public void warn(String eventType, String entityType, String entityId, String message, Object payload) {
        write("WARN", eventType, entityType, entityId, message, payload);
    }

    public void error(String eventType, String entityType, String entityId, String message, Object payload) {
        write("ERROR", eventType, entityType, entityId, message, payload);
    }

    private void write(String level, String eventType, String entityType, String entityId, String message, Object payload) {
        if (!agentProperties.enabled()) {
            return;
        }
        try {
            AgentAuditLogEntity entity = new AgentAuditLogEntity();
            entity.setLevel(level);
            entity.setEventType(eventType);
            entity.setEntityType(entityType);
            entity.setEntityId(entityId);
            entity.setMessage(message);
            entity.setPayloadJson(payloadJson(payload));
            entity.setCreatedAt(Instant.now());
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist agent audit log eventType={} message={}", eventType, ex.getMessage());
        }
    }

    private String payloadJson(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + compact(ex.getMessage()) + "\"}";
        }
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replace("\"", "'").replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(240, compact.length()));
    }
}
