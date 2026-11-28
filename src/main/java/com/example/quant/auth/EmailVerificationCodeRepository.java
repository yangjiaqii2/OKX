package com.example.quant.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCodeEntity, Long> {
    Optional<EmailVerificationCodeEntity> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    Optional<EmailVerificationCodeEntity> findFirstByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(String email, String purpose);
}
