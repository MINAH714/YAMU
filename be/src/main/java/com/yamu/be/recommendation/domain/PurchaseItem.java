package com.yamu.be.recommendation.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;

@Entity
@Table(name="purchase_item")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PurchaseItem {
    @Id
    @Column(length=64,nullable=false)
    private String purchaseItemCode;
    @Column(length=150,nullable=false)
    private String displayName;
    @Column(name="is_active")
    private boolean active;
}
