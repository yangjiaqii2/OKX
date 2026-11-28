package com.example.quant.agent.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingAgentRepository extends JpaRepository<TradingAgentEntity, Long> {
    List<TradingAgentEntity> findByUserNameOrderByUpdatedAtDesc(String userName);

    Optional<TradingAgentEntity> findByIdAndUserName(Long id, String userName);

    List<TradingAgentEntity> findByStatusOrderByUpdatedAtAsc(TradingAgentStatus status);

    long countByUserName(String userName);
}
