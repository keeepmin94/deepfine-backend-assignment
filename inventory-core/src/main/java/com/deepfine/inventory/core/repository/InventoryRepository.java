package com.deepfine.inventory.core.repository;

import com.deepfine.inventory.core.entity.Inventory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO inventory (product_id) VALUES (:productId)
            ON CONFLICT (product_id) DO NOTHING
            """, nativeQuery = true)
    int insertZeroIfAbsent(@Param("productId") long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT inventory FROM Inventory inventory WHERE inventory.productId = :productId")
    Optional<Inventory> findForUpdate(@Param("productId") long productId);
}
