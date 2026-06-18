package com.example.quant.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingOrderRepository extends JpaRepository<PendingOrderEntity, String> {
}
