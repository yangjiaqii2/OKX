package com.example.quant.ai;

import com.example.quant.account.OkxCredentialCodec;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLlmProfileService {
    private static final String PROVIDER_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    private final UserLlmProfileRepository repository;
    private final OkxCredentialCodec credentialCodec;
    private final AiAnalysisService aiAnalysisService;

    public UserLlmProfileService(
            UserLlmProfileRepository repository,
            OkxCredentialCodec credentialCodec,
            AiAnalysisService aiAnalysisService
    ) {
        this.repository = repository;
        this.credentialCodec = credentialCodec;
        this.aiAnalysisService = aiAnalysisService;
    }

    @Transactional(readOnly = true)
    public List<UserLlmProfileView> listForCurrentUser() {
        return listForUser(currentUsername());
    }

    @Transactional(readOnly = true)
    public List<UserLlmProfileView> listForUser(String username) {
        return repository.findByUserNameOrderByActiveDescUpdatedAtDesc(normalizeUsername(username)).stream()
                .map(UserLlmProfileView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserLlmProfileStatus statusForCurrentUser() {
        return statusForUser(currentUsername());
    }

    @Transactional(readOnly = true)
    public UserLlmProfileStatus statusForUser(String username) {
        String owner = normalizeUsername(username);
        List<UserLlmProfileEntity> profiles = repository.findByUserNameOrderByActiveDescUpdatedAtDesc(owner);
        if (profiles.isEmpty()) {
            return UserLlmProfileStatus.empty();
        }
        UserLlmProfileEntity active = profiles.stream()
                .filter(UserLlmProfileEntity::isActive)
                .findFirst()
                .orElse(null);
        return new UserLlmProfileStatus(
                active != null,
                active == null ? null : active.getId(),
                active == null ? null : active.getName(),
                active == null ? null : active.getModelName(),
                active == null ? null : active.getMaskedApiKey(),
                profiles.size()
        );
    }

    @Transactional
    public UserLlmProfileView saveForCurrentUser(SaveUserLlmProfileRequest request) {
        return saveForUser(currentUsername(), request);
    }

    @Transactional
    public UserLlmProfileView saveForUser(String username, SaveUserLlmProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        String owner = normalizeUsername(username);
        String name = requireText(request.name(), "配置名称不能为空", 128);
        String baseUrl = validateBaseUrl(request.baseUrl());
        String modelName = requireText(request.modelName(), "模型名称不能为空", 128);
        String rawApiKey = request.apiKey() == null ? "" : request.apiKey().trim();

        Instant now = Instant.now();
        UserLlmProfileEntity entity;
        boolean hasExistingProfiles = !repository.findByUserNameOrderByActiveDescUpdatedAtDesc(owner).isEmpty();
        if (request.id() != null) {
            entity = repository.findByIdAndUserName(request.id(), owner)
                    .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        } else {
            entity = new UserLlmProfileEntity();
            entity.setUserName(owner);
            entity.setProviderType(PROVIDER_OPENAI_COMPATIBLE);
            entity.setCreatedAt(now);
            if (rawApiKey.isEmpty()) {
                throw new IllegalArgumentException("API Key 不能为空");
            }
        }

        entity.setName(name);
        entity.setBaseUrl(baseUrl);
        entity.setModelName(modelName);
        if (!rawApiKey.isEmpty()) {
            entity.setApiKeyEncoded(credentialCodec.encode(rawApiKey));
            entity.setMaskedApiKey(maskApiKey(rawApiKey));
        }

        boolean shouldActivate = Boolean.TRUE.equals(request.active())
                || (!hasExistingProfiles && request.active() == null);
        if (shouldActivate) {
            deactivateAll(owner, now);
            entity.setActive(true);
        } else if (request.active() != null) {
            entity.setActive(false);
        }
        entity.setUpdatedAt(now);
        return UserLlmProfileView.from(repository.save(entity));
    }

    @Transactional
    public UserLlmProfileView activateForCurrentUser(Long id) {
        return activateForUser(currentUsername(), id);
    }

    @Transactional
    public UserLlmProfileView activateForUser(String username, Long id) {
        String owner = normalizeUsername(username);
        UserLlmProfileEntity target = repository.findByIdAndUserName(id, owner)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        Instant now = Instant.now();
        deactivateAll(owner, now);
        target.setActive(true);
        target.setUpdatedAt(now);
        return UserLlmProfileView.from(repository.save(target));
    }

    @Transactional
    public void deleteForCurrentUser(Long id) {
        deleteForUser(currentUsername(), id);
    }

    @Transactional
    public void deleteForUser(String username, Long id) {
        String owner = normalizeUsername(username);
        UserLlmProfileEntity target = repository.findByIdAndUserName(id, owner)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        boolean wasActive = target.isActive();
        repository.delete(target);
        if (wasActive) {
            List<UserLlmProfileEntity> remaining = repository.findByUserNameOrderByActiveDescUpdatedAtDesc(owner);
            if (!remaining.isEmpty()) {
                UserLlmProfileEntity next = remaining.get(0);
                next.setActive(true);
                next.setUpdatedAt(Instant.now());
                repository.save(next);
            }
        }
    }

    @Transactional(readOnly = true)
    public LlmConnectionTestResult testConnectionForCurrentUser(Long id) {
        return testConnectionForUser(currentUsername(), id);
    }

    @Transactional(readOnly = true)
    public LlmConnectionTestResult testConnectionForUser(String username, Long id) {
        String owner = normalizeUsername(username);
        UserLlmProfileEntity entity = repository.findByIdAndUserName(id, owner)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        return aiAnalysisService.testConnection(toRuntimeConfig(entity));
    }

    @Transactional(readOnly = true)
    public Optional<LlmRuntimeConfig> resolveActiveRuntimeConfig(String username) {
        return repository.findFirstByUserNameAndActiveTrueOrderByUpdatedAtDesc(normalizeUsername(username))
                .map(this::toRuntimeConfig);
    }

    @Transactional(readOnly = true)
    public Optional<LlmRuntimeConfig> resolveRuntimeConfig(String username, Long profileId) {
        String owner = normalizeUsername(username);
        if (profileId != null) {
            return repository.findByIdAndUserName(profileId, owner).map(this::toRuntimeConfig);
        }
        return resolveActiveRuntimeConfig(owner);
    }

    private LlmRuntimeConfig toRuntimeConfig(UserLlmProfileEntity entity) {
        return new LlmRuntimeConfig(
                entity.getBaseUrl(),
                credentialCodec.decode(entity.getApiKeyEncoded()),
                entity.getModelName()
        );
    }

    private void deactivateAll(String owner, Instant now) {
        repository.findAllByUserNameAndActiveTrue(owner).forEach(existing -> {
            existing.setActive(false);
            existing.setUpdatedAt(now);
            repository.save(existing);
        });
    }

    private static String validateBaseUrl(String value) {
        String url = requireText(value, "API Base URL 不能为空", 512);
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("API Base URL 必须为有效的 http/https 地址");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("API Base URL 必须为有效的 http/https 地址");
        }
        return url;
    }

    private static String requireText(String value, String message, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(message + "（长度超限）");
        }
        return trimmed;
    }

    static String maskApiKey(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return OkxCredentialStore.SYSTEM_USER;
        }
        return username.trim();
    }
}
