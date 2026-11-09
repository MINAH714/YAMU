package com.yamu.be.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * created_at만 있는 테이블의 엔티티가 상속한다(한 번 쓰고 바뀌지 않는 기록: 감사 로그, 이벤트 등).
 * 값은 저장할 때 JPA Auditing이 UTC로 채운다. 코드에서 직접 넣지 않는다.
 *
 * <p>값이 바뀌는 테이블은 updated_at까지 있는 {@link BaseTimeEntity}를 상속한다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseCreatedTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
