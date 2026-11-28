package com.example.quant.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLlmProfileRepository extends JpaRepository<UserLlmProfileEntity, Long> {
    List<UserLlmProfileEntity> findByUserNameOrderByActiveDescUpdatedAtDesc(String userName);

    Optional<UserLlmProfileEntity> findByIdAndUserName(Long id, String userName);

    Optional<UserLlmProfileEntity> findFirstByUserNameAndActiveTrueOrderByUpdatedAtDesc(String userName);

    List<UserLlmProfileEntity> findAllByUserNameAndActiveTrue(String userName);
}
