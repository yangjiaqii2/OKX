package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import com.example.quant.config.MailProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUserServiceTest {

    @Test
    void initializesDefaultAdminWithBcryptHashWhenNoUsersExist() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "test-auth-fixture-password", 480)
        );

        service.initializeDefaultAdmin();

        AuthUserEntity admin = repository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(AuthRole.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).isNotEqualTo("test-auth-fixture-password");
        assertThat(service.authenticate("admin", "test-auth-fixture-password")).isPresent();
    }

    @Test
    void changeOwnPasswordRequiresOldPasswordAndInvalidatesPreviousPassword() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);

        service.changeOwnPassword("admin", new ChangePasswordRequest("test-auth-fixture-password", "newSecret123"));

        assertThat(service.authenticate("admin", "test-auth-fixture-password")).isEmpty();
        assertThat(service.authenticate("admin", "newSecret123")).isPresent();
    }

    @Test
    void disabledUserCannotLogin() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);
        service.createUser(new CreateUserRequest("operator", "pass12345", AuthRole.USER, true));
        service.setEnabled("operator", false);

        assertThat(service.authenticate("operator", "pass12345")).isEmpty();
    }

    @Test
    void nonAdminCannotCreateUsers() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);
        service.createUser(new CreateUserRequest("operator", "pass12345", AuthRole.USER, true));

        assertThatThrownBy(() -> service.createUserAs("operator",
                new CreateUserRequest("other", "pass12345", AuthRole.USER, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("管理员");
    }

    @Test
    void registersStandardUserWhenRegistrationIsOpen() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);

        AuthUserEntity created = service.registerUser(new AuthRegisterRequest("trader_01", "pass12345", ""));

        assertThat(created.getUsername()).isEqualTo("trader_01");
        assertThat(created.getRole()).isEqualTo(AuthRole.USER);
        assertThat(created.isEnabled()).isTrue();
        assertThat(service.authenticate("trader_01", "pass12345")).isPresent();
    }

    @Test
    void rejectsRegistrationWhenInviteCodeDoesNotMatchOrRegistrationDisabled() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService inviteOnly = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "test-auth-fixture-password", 480, true, "BETA-2026")
        );
        inviteOnly.initializeDefaultAdmin();

        assertThat(inviteOnly.registrationConfig()).isEqualTo(new AuthRegistrationConfigResponse(true, true));
        assertThatThrownBy(() -> inviteOnly.registerUser(new AuthRegisterRequest("alice", "pass12345", "wrong")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邀请码");
        assertThat(inviteOnly.registerUser(new AuthRegisterRequest("alice", "pass12345", "BETA-2026")).getRole())
                .isEqualTo(AuthRole.USER);

        AuthUserService closed = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "test-auth-fixture-password", 480, false, "")
        );
        assertThatThrownBy(() -> closed.registerUser(new AuthRegisterRequest("bob", "pass12345", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未开放自助注册");
    }

    @Test
    void rejectsInvalidUsernameOrDuplicateDuringRegistration() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);

        assertThatThrownBy(() -> service.registerUser(new AuthRegisterRequest("ab", "pass12345", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3-32");
        assertThatThrownBy(() -> service.registerUser(new AuthRegisterRequest("admin", "pass12345", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void enforcesEmailVerificationForRegistrationAndForgotPasswordReset() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);
        List<EmailVerificationCodeEntity> codes = new ArrayList<>();
        EmailVerificationCodeRepository codeRepository = inMemoryCodeRepository(codes);
        EmailVerificationService emailService = new EmailVerificationService(
                codeRepository,
                MailProperties.defaults(),
                new ObjectMapper()
        );
        service.configureEmailVerification(emailService);

        // Missing or invalid code must be rejected
        assertThatThrownBy(() -> service.registerUser(new AuthRegisterRequest(
                "trader_mail", "pass12345", "", "trader@example.com", "000000"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码");

        // Send register code & check cooldown
        EmailVerificationService.SendCodeResult regCode = service.sendEmailCode(
                new SendEmailCodeRequest("trader@example.com", EmailVerificationService.PURPOSE_REGISTER));
        assertThat(regCode.sent()).isTrue();
        assertThat(regCode.devCode()).hasSize(6);
        assertThatThrownBy(() -> service.sendEmailCode(
                new SendEmailCodeRequest("trader@example.com", EmailVerificationService.PURPOSE_REGISTER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("频繁");

        // Register succeeds with valid code
        AuthUserEntity created = service.registerUser(new AuthRegisterRequest(
                "trader_mail", "pass12345", "", "trader@example.com", regCode.devCode()
        ));
        assertThat(created.getEmail()).isEqualTo("trader@example.com");
        assertThat(service.authenticate("trader_mail", "pass12345")).isPresent();

        // Duplicate email registration rejected
        assertThatThrownBy(() -> service.sendEmailCode(
                new SendEmailCodeRequest("trader@example.com", EmailVerificationService.PURPOSE_REGISTER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被注册");

        // Forgot password flow via email verification code
        EmailVerificationService.SendCodeResult resetCode = service.sendEmailCode(
                new SendEmailCodeRequest("trader@example.com", EmailVerificationService.PURPOSE_RESET_PASSWORD));
        assertThat(resetCode.devCode()).hasSize(6);

        service.resetPasswordByEmail(new ForgotPasswordResetRequest(
                "trader@example.com", resetCode.devCode(), "newPass999"
        ));
        assertThat(service.authenticate("trader_mail", "pass12345")).isEmpty();
        assertThat(service.authenticate("trader_mail", "newPass999")).isPresent();
    }

    private static EmailVerificationCodeRepository inMemoryCodeRepository(List<EmailVerificationCodeEntity> store) {
        return (EmailVerificationCodeRepository) Proxy.newProxyInstance(
                EmailVerificationCodeRepository.class.getClassLoader(),
                new Class<?>[]{EmailVerificationCodeRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        EmailVerificationCodeEntity entity = (EmailVerificationCodeEntity) args[0];
                        if (entity.getId() == null) {
                            entity.setId((long) (store.size() + 1));
                            store.add(entity);
                        }
                        yield entity;
                    }
                    case "findFirstByEmailAndPurposeOrderByCreatedAtDesc" -> {
                        String email = (String) args[0];
                        String purpose = (String) args[1];
                        yield store.stream()
                                .filter(c -> email.equals(c.getEmail()) && purpose.equals(c.getPurpose()))
                                .max(Comparator.comparing(EmailVerificationCodeEntity::getCreatedAt));
                    }
                    case "findFirstByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc" -> {
                        String email = (String) args[0];
                        String purpose = (String) args[1];
                        yield store.stream()
                                .filter(c -> !c.isUsed() && email.equals(c.getEmail()) && purpose.equals(c.getPurpose()))
                                .max(Comparator.comparing(EmailVerificationCodeEntity::getCreatedAt));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static AuthUserService seededService(InMemoryAuthUserRepository repository) {
        AuthUserService service = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "test-auth-fixture-password", 480)
        );
        service.initializeDefaultAdmin();
        return service;
    }

    private static final class InMemoryAuthUserRepository implements AuthUserRepository {
        private final List<AuthUserEntity> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public Optional<AuthUserEntity> findByUsername(String username) {
            return rows.stream()
                    .filter(row -> row.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public Optional<AuthUserEntity> findByEmail(String email) {
            if (email == null) {
                return Optional.empty();
            }
            return rows.stream()
                    .filter(row -> email.equalsIgnoreCase(row.getEmail()))
                    .findFirst();
        }

        @Override
        public boolean existsByEmail(String email) {
            return findByEmail(email).isPresent();
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public AuthUserEntity save(AuthUserEntity entity) {
            if (entity.getId() == null) {
                entity.setId(nextId++);
                entity.setCreatedAt(Instant.now());
            }
            entity.setUpdatedAt(Instant.now());
            findByUsername(entity.getUsername()).ifPresent(rows::remove);
            rows.add(entity);
            return entity;
        }

        @Override
        public List<AuthUserEntity> findAllByOrderByCreatedAtAsc() {
            return List.copyOf(rows);
        }
    }
}
