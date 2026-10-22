package com.yamu.be.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * created_at·updated_at이 모두 있는 테이블(값이 바뀌는 테이블)의 엔티티가 상속한다.
 * updated_at은 엔티티가 변경되어 UPDATE가 나갈 때마다 JPA Auditing이 UTC로 갱신한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreatedTimeEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
