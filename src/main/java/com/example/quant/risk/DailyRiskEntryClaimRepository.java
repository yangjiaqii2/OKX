package com.example.quant.risk;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyRiskEntryClaimRepository extends JpaRepository<DailyRiskEntryClaimEntity, Long> {
    Optional<DailyRiskEntryClaimEntity> findByUserNameAndTradeDayAndEntryKey(
            String userName, LocalDate tradeDay, String entryKey);

    Optional<DailyRiskEntryClaimEntity> findFirstByUserNameAndEntryKey(String userName, String entryKey);
}
