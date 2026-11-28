package com.example.quant.auth;

import com.example.quant.config.MailProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {
    public static final String PURPOSE_REGISTER = "REGISTER";
    public static final String PURPOSE_RESET_PASSWORD = "RESET_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final long RESEND_COOLDOWN_SECONDS = 60L;
    private static final long CODE_TTL_SECONDS = 600L;

    private final EmailVerificationCodeRepository codeRepository;
    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public EmailVerificationService(
            EmailVerificationCodeRepository codeRepository,
            MailProperties mailProperties,
            ObjectMapper objectMapper
    ) {
        this.codeRepository = codeRepository;
        this.mailProperties = mailProperties == null ? MailProperties.defaults() : mailProperties;
        this.objectMapper = objectMapper;
    }

    public String normalizeAndValidateEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 255) {
            throw new IllegalArgumentException("请输入有效的邮箱地址");
        }
        return email;
    }

    @Transactional
    public SendCodeResult sendCode(String rawEmail, String rawPurpose) {
        String email = normalizeAndValidateEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        Instant now = Instant.now();

        Optional<EmailVerificationCodeEntity> latest =
                codeRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (latest.isPresent()) {
            long elapsed = Duration.between(latest.get().getCreatedAt(), now).toSeconds();
            if (elapsed < RESEND_COOLDOWN_SECONDS) {
                throw new IllegalArgumentException("发送过于频繁，请 " + (RESEND_COOLDOWN_SECONDS - elapsed) + " 秒后再试");
            }
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        EmailVerificationCodeEntity entity = new EmailVerificationCodeEntity();
        entity.setEmail(email);
        entity.setPurpose(purpose);
        entity.setCode(code);
        entity.setExpiresAt(now.plusSeconds(CODE_TTL_SECONDS));
        entity.setUsed(false);
        entity.setCreatedAt(now);
        codeRepository.save(entity);

        String subject = PURPOSE_REGISTER.equals(purpose)
                ? "Quant Desk 注册验证码"
                : "Quant Desk 重置密码验证码";
        String text = "您的验证码为：" + code + "（10 分钟内有效）。如非本人操作请忽略本邮件。";

        String provider = mailProperties.effectiveProvider();
        String devCode = null;
        if ("RESEND".equals(provider)) {
            sendViaResend(email, subject, text);
        } else if ("SMTP".equals(provider)) {
            sendViaSmtp(email, subject, text);
        } else {
            log.info("DEV_LOG Email verification code email={} purpose={} code={}", email, purpose, code);
            if (mailProperties.effectiveExposeDevCode()) {
                devCode = code;
            }
        }
        return new SendCodeResult(true, provider, RESEND_COOLDOWN_SECONDS, devCode,
                devCode != null ? "当前为本地开发邮件模式，已生成验证码" : "验证码已发送至邮箱，请查收（10 分钟内有效）");
    }

    @Transactional
    public void verifyAndConsume(String rawEmail, String rawPurpose, String rawCode) {
        String email = normalizeAndValidateEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("请输入邮箱验证码");
        }
        EmailVerificationCodeEntity entity = codeRepository
                .findFirstByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new IllegalArgumentException("验证码无效或已过期，请重新获取"));
        if (Instant.now().isAfter(entity.getExpiresAt())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!entity.getCode().equals(code)) {
            throw new IllegalArgumentException("邮箱验证码错误");
        }
        entity.setUsed(true);
        codeRepository.save(entity);
    }

    private void sendViaResend(String to, String subject, String text) {
        if (mailProperties.resendApiKey() == null || mailProperties.resendApiKey().isBlank()) {
            throw new IllegalStateException("QUANT_MAIL_RESEND_API_KEY 未配置");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "from", mailProperties.effectiveFromAddress(),
                    "to", new String[]{to},
                    "subject", subject,
                    "text", text
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + mailProperties.resendApiKey().trim())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("邮件发送失败 HTTP " + response.statusCode());
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("邮件发送失败: " + ex.getMessage(), ex);
        }
    }

    private void sendViaSmtp(String to, String subject, String text) {
        if (mailProperties.smtpHost() == null || mailProperties.smtpHost().isBlank()) {
            throw new IllegalStateException("QUANT_MAIL_SMTP_HOST 未配置");
        }
        int port = mailProperties.smtpPort() > 0 ? mailProperties.smtpPort() : 465;
        try (Socket socket = mailProperties.effectiveSmtpSsl()
                ? SSLSocketFactory.getDefault().createSocket(mailProperties.smtpHost().trim(), port)
                : new Socket(mailProperties.smtpHost().trim(), port)) {
            socket.setSoTimeout(12_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            expectSmtp(reader, "220");
            smtpCmd(writer, reader, "EHLO quantdesk.local", "250");
            if (mailProperties.smtpUsername() != null && !mailProperties.smtpUsername().isBlank()) {
                smtpCmd(writer, reader, "AUTH LOGIN", "334");
                smtpCmd(writer, reader, Base64.getEncoder().encodeToString(mailProperties.smtpUsername().trim().getBytes(StandardCharsets.UTF_8)), "334");
                smtpCmd(writer, reader, Base64.getEncoder().encodeToString((mailProperties.smtpPassword() == null ? "" : mailProperties.smtpPassword()).getBytes(StandardCharsets.UTF_8)), "235");
            }
            String from = mailProperties.effectiveFromAddress();
            smtpCmd(writer, reader, "MAIL FROM:<" + from + ">", "250");
            smtpCmd(writer, reader, "RCPT TO:<" + to + ">", "250");
            smtpCmd(writer, reader, "DATA", "354");
            String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
            writer.write("From: " + from + "\r\n");
            writer.write("To: " + to + "\r\n");
            writer.write("Subject: " + encodedSubject + "\r\n");
            writer.write("MIME-Version: 1.0\r\n");
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
            writer.write(text + "\r\n.\r\n");
            writer.flush();
            expectSmtp(reader, "250");
            smtpCmd(writer, reader, "QUIT", "221");
        } catch (Exception ex) {
            throw new IllegalStateException("SMTP 邮件发送失败: " + ex.getMessage(), ex);
        }
    }

    private static void smtpCmd(BufferedWriter writer, BufferedReader reader, String cmd, String expectedPrefix) throws Exception {
        writer.write(cmd + "\r\n");
        writer.flush();
        expectSmtp(reader, expectedPrefix);
    }

    private static void expectSmtp(BufferedReader reader, String expectedPrefix) throws Exception {
        String line;
        String last = "";
        while ((line = reader.readLine()) != null) {
            last = line;
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
        }
        if (!last.startsWith(expectedPrefix)) {
            throw new IllegalStateException("SMTP unexpected response: " + last);
        }
    }

    private static String normalizePurpose(String raw) {
        String p = raw == null ? "" : raw.trim().toUpperCase();
        if (PURPOSE_REGISTER.equals(p) || PURPOSE_RESET_PASSWORD.equals(p)) {
            return p;
        }
        throw new IllegalArgumentException("不支持的验证码用途");
    }

    public record SendCodeResult(
            boolean sent,
            String provider,
            long cooldownSeconds,
            String devCode,
            String message
    ) {
    }
}
