package com.deepfine.inventory.core;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductCode(String productCode);

    @Modifying
    @Query(value = """
            INSERT INTO product (product_code, name) VALUES (:productCode, :name)
            ON CONFLICT (product_code) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("productCode") String productCode, @Param("name") String name);
}
