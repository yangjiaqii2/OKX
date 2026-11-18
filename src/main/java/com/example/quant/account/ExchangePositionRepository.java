package com.example.quant.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangePositionRepository extends JpaRepository<ExchangePositionEntity, Long> {
    Optional<ExchangePositionEntity> findByUserNameAndInstIdAndPosSide(String userName, String instId, String posSide);
    List<ExchangePositionEntity> findByUserName(String userName);
    long countByUserNameAndOwnership(String userName, String ownership);
}
