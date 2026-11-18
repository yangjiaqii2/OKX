package com.example.quant.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBillRepository extends JpaRepository<AccountBillEntity, Long> {
    Optional<AccountBillEntity> findByUserNameAndBillId(String userName, String billId);
    List<AccountBillEntity> findByUserName(String userName);
}
