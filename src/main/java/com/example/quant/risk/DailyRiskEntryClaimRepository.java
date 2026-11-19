package com.example.quant.risk;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyRiskEntryClaimRepository extends JpaRepository<DailyRiskEntryClaimEntity, Long> {
    Optional<DailyRiskEntryClaimEntity> findByUserNameAndTradeDayAndEntryKey(
            String userName, LocalDate tradeDay, String entryKey);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(c.reservedLossUsdt),0) from DailyRiskEntryClaimEntity c where c.userName = :owner and c.outcomeRecorded = false")
    java.math.BigDecimal outstandingLoss(@org.springframework.data.repository.query.Param("owner") String owner);

    @org.springframework.data.jpa.repository.Query("select count(c) from DailyRiskEntryClaimEntity c, PendingOrderEntity p "
            + "where c.userName = :owner and p.userName = c.userName and p.id = c.entryKey "
            + "and c.outcomeRecorded = false and p.status in ('CLOSE_SUBMITTED','CLOSED_RECONCILING','CLOSED')")
    long unsettledCloses(@org.springframework.data.repository.query.Param("owner") String owner);

    boolean existsByUserNameAndEntryKeyAndOutcomeRecordedFalse(String userName, String entryKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<DailyRiskEntryClaimEntity> findFirstByUserNameAndEntryKey(String userName, String entryKey);
}
