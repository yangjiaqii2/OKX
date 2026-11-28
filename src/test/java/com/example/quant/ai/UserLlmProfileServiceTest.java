package com.example.quant.ai;

import com.example.quant.account.OkxCredentialCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserLlmProfileServiceTest {
    private final List<UserLlmProfileEntity> store = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);
    private UserLlmProfileRepository repository;
    private AiAnalysisService aiAnalysisService;
    private UserLlmProfileService service;

    @BeforeEach
    void setUp() {
        store.clear();
        seq.set(1);
        repository = mock(UserLlmProfileRepository.class);
        aiAnalysisService = mock(AiAnalysisService.class);

        when(repository.save(any(UserLlmProfileEntity.class))).thenAnswer(inv -> {
            UserLlmProfileEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(seq.getAndIncrement());
                store.add(entity);
            }
            return entity;
        });
        when(repository.findByUserNameOrderByActiveDescUpdatedAtDesc(anyString())).thenAnswer(inv -> {
            String user = inv.getArgument(0);
            return store.stream()
                    .filter(row -> user.equals(row.getUserName()))
                    .sorted(Comparator.comparing(UserLlmProfileEntity::isActive).reversed()
                            .thenComparing(UserLlmProfileEntity::getUpdatedAt, Comparator.reverseOrder()))
                    .toList();
        });
        when(repository.findByIdAndUserName(anyLong(), anyString())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String user = inv.getArgument(1);
            return store.stream()
                    .filter(row -> id.equals(row.getId()) && user.equals(row.getUserName()))
                    .findFirst();
        });
        when(repository.findFirstByUserNameAndActiveTrueOrderByUpdatedAtDesc(anyString())).thenAnswer(inv -> {
            String user = inv.getArgument(0);
            return store.stream()
                    .filter(row -> user.equals(row.getUserName()) && row.isActive())
                    .findFirst();
        });
        when(repository.findAllByUserNameAndActiveTrue(anyString())).thenAnswer(inv -> {
            String user = inv.getArgument(0);
            return store.stream()
                    .filter(row -> user.equals(row.getUserName()) && row.isActive())
                    .toList();
        });
        doAnswer(inv -> {
            UserLlmProfileEntity target = inv.getArgument(0);
            store.removeIf(row -> target.getId().equals(row.getId()));
            return null;
        }).when(repository).delete(any(UserLlmProfileEntity.class));

        OkxCredentialCodec codec = new OkxCredentialCodec() {
            @Override
            public String encode(String value) {
                return "enc:" + value;
            }

            @Override
            public String decode(String value) {
                return value.startsWith("enc:") ? value.substring(4) : value;
            }
        };

        service = new UserLlmProfileService(repository, codec, aiAnalysisService);
    }

    @Test
    void savesEncryptedProfileActivatesFirstAndIsolatesBetweenUsers() {
        UserLlmProfileView aliceProfile = service.saveForUser("alice", new SaveUserLlmProfileRequest(
                null, "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-1234567890abcd", null
        ));

        assertThat(aliceProfile.active()).isTrue();
        assertThat(aliceProfile.maskedApiKey()).isEqualTo("sk-1****abcd");
        assertThat(store.get(0).getApiKeyEncoded()).isEqualTo("enc:sk-1234567890abcd");

        Optional<LlmRuntimeConfig> aliceRuntime = service.resolveActiveRuntimeConfig("alice");
        assertThat(aliceRuntime).contains(new LlmRuntimeConfig(
                "https://api.deepseek.com/v1", "sk-1234567890abcd", "deepseek-chat"
        ));

        assertThat(service.listForUser("bob")).isEmpty();
        assertThat(service.statusForUser("bob").configured()).isFalse();
        assertThatThrownBy(() -> service.activateForUser("bob", aliceProfile.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void editWithoutApiKeyPreservesEncryptedKeyAndDeletePromotesRemainingProfile() {
        UserLlmProfileView first = service.saveForUser("alice", new SaveUserLlmProfileRequest(
                null, "Primary", "https://api.deepseek.com/v1", "deepseek-chat", "sk-first-secret-key", true
        ));
        UserLlmProfileView second = service.saveForUser("alice", new SaveUserLlmProfileRequest(
                null, "Backup", "https://api.openai.com/v1", "gpt-4o-mini", "sk-second-secret-key", false
        ));

        UserLlmProfileView updated = service.saveForUser("alice", new SaveUserLlmProfileRequest(
                first.id(), "Primary Renamed", "https://api.deepseek.com/v1", "deepseek-reasoner", "", true
        ));
        assertThat(updated.name()).isEqualTo("Primary Renamed");
        assertThat(service.resolveActiveRuntimeConfig("alice"))
                .contains(new LlmRuntimeConfig("https://api.deepseek.com/v1", "sk-first-secret-key", "deepseek-reasoner"));

        service.deleteForUser("alice", first.id());

        assertThat(service.listForUser("alice")).hasSize(1);
        assertThat(service.statusForUser("alice").activeProfileId()).isEqualTo(second.id());
        assertThat(service.resolveActiveRuntimeConfig("alice"))
                .contains(new LlmRuntimeConfig("https://api.openai.com/v1", "sk-second-secret-key", "gpt-4o-mini"));
    }

    @Test
    void rejectsInvalidBaseUrlAndTestsConnectionWithDecodedKey() {
        assertThatThrownBy(() -> service.saveForUser("alice", new SaveUserLlmProfileRequest(
                null, "Bad", "ftp://example.com", "model", "sk-123456789", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");

        UserLlmProfileView saved = service.saveForUser("alice", new SaveUserLlmProfileRequest(
                null, "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-test-key-9999", true
        ));
        when(aiAnalysisService.testConnection(new LlmRuntimeConfig(
                "https://api.deepseek.com/v1", "sk-test-key-9999", "deepseek-chat"
        ))).thenReturn(new LlmConnectionTestResult(true, "deepseek-chat", 120L, "连通性验证通过"));

        LlmConnectionTestResult result = service.testConnectionForUser("alice", saved.id());
        assertThat(result.ok()).isTrue();
        assertThat(result.elapsedMs()).isEqualTo(120L);
    }
}
