package com.example.quant.risk;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyRiskStateRepository extends JpaRepository<DailyRiskStateEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from DailyRiskStateEntity state where state.userName = :owner and state.tradeDay = :day")
    Optional<DailyRiskStateEntity> findForUpdate(@Param("owner") String owner, @Param("day") LocalDate day);

    Optional<DailyRiskStateEntity> findFirstByUserNameOrderByTradeDayDesc(String userName);
}
