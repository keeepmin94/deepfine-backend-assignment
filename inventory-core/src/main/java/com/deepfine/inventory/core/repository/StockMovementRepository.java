package com.deepfine.inventory.core.repository;

import com.deepfine.inventory.core.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
}
