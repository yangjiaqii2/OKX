package com.example.quant.crypto;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketCapSnapshotRepository extends JpaRepository<MarketCapSnapshotEntity, String> {
}
