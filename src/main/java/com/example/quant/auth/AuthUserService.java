package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthUserService {
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final java.util.regex.Pattern REGISTER_USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]{3,32}$");

    private final AuthUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final AuthProperties authProperties;
    private EmailVerificationService emailVerificationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureEmailVerification(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    public AuthUserService(
            AuthUserRepository repository,
            PasswordHasher passwordHasher,
            AuthProperties authProperties
    ) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.authProperties = authProperties;
    }

    @PostConstruct
    @Transactional
    public void initializeDefaultAdmin() {
        if (repository.count() > 0) {
            return;
        }
        AuthUserEntity admin = new AuthUserEntity();
        admin.setUsername(normalizeUsername(authProperties.effectiveUsername()));
        admin.setPasswordHash(passwordHasher.hash(authProperties.effectivePassword()));
        admin.setRole(AuthRole.ADMIN);
        admin.setEnabled(true);
        repository.save(admin);
    }

    @Transactional
    public Optional<AuthUserEntity> authenticate(String username, String rawPassword) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isBlank() || rawPassword == null) {
            return Optional.empty();
        }
        Optional<AuthUserEntity> user = repository.findByUsername(normalizedUsername)
                .filter(AuthUserEntity::isEnabled)
                .filter(entity -> matches(rawPassword, entity.getPasswordHash()));
        user.ifPresent(entity -> {
            entity.setLastLoginAt(Instant.now());
            repository.save(entity);
        });
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<AuthUserEntity> findEnabledUser(String username) {
        return repository.findByUsername(normalizeUsername(username)).filter(AuthUserEntity::isEnabled);
    }

    @Transactional(readOnly = true)
    public List<AuthUserView> listUsersAs(String actorUsername) {
        requireAdmin(actorUsername);
        return repository.findAllByOrderByCreatedAtAsc().stream()
                .map(AuthUserView::from)
                .toList();
    }

    @Transactional
    public AuthUserView createUser(CreateUserRequest request) {
        AuthUserEntity created = createUserEntity(request);
        return AuthUserView.from(created);
    }

    @Transactional
    public AuthUserView createUserAs(String actorUsername, CreateUserRequest request) {
        requireAdmin(actorUsername);
        return createUser(request);
    }

    public AuthRegistrationConfigResponse registrationConfig() {
        return new AuthRegistrationConfigResponse(
                authProperties.effectiveRegistrationEnabled(),
                authProperties.isInviteCodeRequired()
        );
    }

    @Transactional
    public EmailVerificationService.SendCodeResult sendEmailCode(SendEmailCodeRequest request) {
        if (emailVerificationService == null) {
            throw new IllegalStateException("邮箱验证服务未启用");
        }
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        String email = emailVerificationService.normalizeAndValidateEmail(request.email());
        String purpose = request.purpose() == null ? "" : request.purpose().trim().toUpperCase();
        if (EmailVerificationService.PURPOSE_REGISTER.equals(purpose)) {
            if (!authProperties.effectiveRegistrationEnabled()) {
                throw new IllegalArgumentException("当前未开放自助注册");
            }
            if (repository.existsByEmail(email)) {
                throw new IllegalArgumentException("该邮箱已被注册");
            }
        } else if (EmailVerificationService.PURPOSE_RESET_PASSWORD.equals(purpose)) {
            AuthUserEntity user = repository.findByEmail(email)
                    .filter(AuthUserEntity::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("未找到绑定该邮箱的可用账号"));
            if (user.getUsername().isBlank()) {
                throw new IllegalArgumentException("账号状态异常");
            }
        } else {
            throw new IllegalArgumentException("不支持的验证码用途");
        }
        return emailVerificationService.sendCode(email, purpose);
    }

    @Transactional
    public AuthUserEntity registerUser(AuthRegisterRequest request) {
        if (!authProperties.effectiveRegistrationEnabled()) {
            throw new IllegalArgumentException("当前未开放自助注册，请联系管理员创建账号");
        }
        if (request == null) {
            throw new IllegalArgumentException("注册信息不能为空");
        }
        String username = normalizeUsername(request.username());
        if (!REGISTER_USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名需为 3-32 位字母、数字、点、下划线或短横线");
        }
        if (authProperties.isInviteCodeRequired()) {
            String provided = request.inviteCode() == null ? "" : request.inviteCode().trim();
            if (!authProperties.effectiveInviteCode().equals(provided)) {
                throw new IllegalArgumentException("邀请码无效");
            }
        }
        String normalizedEmail = null;
        if (emailVerificationService != null) {
            normalizedEmail = emailVerificationService.normalizeAndValidateEmail(request.email());
            if (repository.existsByEmail(normalizedEmail)) {
                throw new IllegalArgumentException("该邮箱已被注册");
            }
            emailVerificationService.verifyAndConsume(
                    normalizedEmail, EmailVerificationService.PURPOSE_REGISTER, request.emailCode());
        } else if (request.email() != null && !request.email().isBlank()) {
            normalizedEmail = request.email().trim().toLowerCase();
        }
        AuthUserEntity created = createUserEntity(new CreateUserRequest(username, request.password(), AuthRole.USER, true));
        created.setEmail(normalizedEmail);
        created.setLastLoginAt(Instant.now());
        return repository.save(created);
    }

    @Transactional
    public void resetPasswordByEmail(ForgotPasswordResetRequest request) {
        if (emailVerificationService == null) {
            throw new IllegalStateException("邮箱验证服务未启用");
        }
        if (request == null) {
            throw new IllegalArgumentException("重置密码信息不能为空");
        }
        String email = emailVerificationService.normalizeAndValidateEmail(request.email());
        AuthUserEntity user = repository.findByEmail(email)
                .filter(AuthUserEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("未找到绑定该邮箱的可用账号"));
        String validPwd = validPassword(request.newPassword());
        emailVerificationService.verifyAndConsume(
                email, EmailVerificationService.PURPOSE_RESET_PASSWORD, request.emailCode());
        user.setPasswordHash(passwordHasher.hash(validPwd));
        repository.save(user);
    }

    @Transactional
    public AuthUserView changeOwnPassword(String username, ChangePasswordRequest request) {
        String normalizedUsername = normalizeUsername(username);
        AuthUserEntity user = repository.findByUsername(normalizedUsername)
                .filter(AuthUserEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用"));
        if (request == null || !matches(nullToEmpty(request.oldPassword()), user.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码错误");
        }
        user.setPasswordHash(passwordHasher.hash(validPassword(request.newPassword())));
        return AuthUserView.from(repository.save(user));
    }

    @Transactional
    public AuthUserView resetPasswordAs(String actorUsername, String targetUsername, ResetPasswordRequest request) {
        requireAdmin(actorUsername);
        AuthUserEntity user = repository.findByUsername(normalizeUsername(targetUsername))
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (user.getRole() == AuthRole.ADMIN && !normalizeUsername(actorUsername).equals(user.getUsername())) {
            throw new IllegalArgumentException("不能重置其他管理员密码");
        }
        user.setPasswordHash(passwordHasher.hash(validPassword(request == null ? null : request.newPassword())));
        return AuthUserView.from(repository.save(user));
    }

    @Transactional
    public AuthUserView setEnabled(String username, boolean enabled) {
        AuthUserEntity user = repository.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setEnabled(enabled);
        return AuthUserView.from(repository.save(user));
    }

    @Transactional
    public AuthUserView setEnabledAs(String actorUsername, String targetUsername, SetUserEnabledRequest request) {
        requireAdmin(actorUsername);
        String actor = normalizeUsername(actorUsername);
        String target = normalizeUsername(targetUsername);
        if (actor.equals(target) && request != null && Boolean.FALSE.equals(request.enabled())) {
            throw new IllegalArgumentException("不能禁用当前登录管理员");
        }
        return setEnabled(target, request == null || Boolean.TRUE.equals(request.enabled()));
    }

    private AuthUserEntity createUserEntity(CreateUserRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("用户信息不能为空");
        }
        String username = normalizeUsername(request.username());
        if (username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (repository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AuthUserEntity entity = new AuthUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(validPassword(request.password())));
        entity.setRole(request.role() == null ? AuthRole.USER : request.role());
        entity.setEnabled(request.enabled() == null || Boolean.TRUE.equals(request.enabled()));
        return repository.save(entity);
    }

    private void requireAdmin(String username) {
        AuthUserEntity user = repository.findByUsername(normalizeUsername(username))
                .filter(AuthUserEntity::isEnabled)
                .orElseThrow(() -> new SecurityException("需要登录"));
        if (user.getRole() != AuthRole.ADMIN) {
            throw new IllegalArgumentException("只有管理员可以执行该操作");
        }
    }

    private boolean matches(String rawPassword, String encodedPassword) {
        try {
            return passwordHasher.matches(rawPassword, encodedPassword);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String validPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度不能少于 6 位");
        }
        return password;
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
