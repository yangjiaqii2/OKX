package com.example.quant.agent.evaluation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowSelectionRepository extends JpaRepository<ShadowSelectionEntity, Long> {
    List<ShadowSelectionEntity> findByUserNameOrderByCreatedAtAsc(String userName);
}
