package com.yamu.be.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, String> {
    
}
