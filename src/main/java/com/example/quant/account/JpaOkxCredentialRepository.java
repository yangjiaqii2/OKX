package com.example.quant.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOkxCredentialRepository extends JpaRepository<JpaOkxCredentialEntity, Long> {
    Optional<JpaOkxCredentialEntity> findFirstByActiveTrueOrderByUpdatedAtDesc();

    Optional<JpaOkxCredentialEntity> findFirstByUserNameAndActiveTrueOrderByUpdatedAtDesc(String userName);

    List<JpaOkxCredentialEntity> findAllByUserNameAndActiveTrue(String userName);
}
