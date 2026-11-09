package com.yamu.be.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 테스트 전용 엔티티. 엔티티 작성 규칙(Setter 없음, protected 기본 생성자, 의도가 드러나는 변경 메서드)의 예시이기도 하다. */
@Entity
@Table(name = "test_audit_sample")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditSample extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Version
    private Long version;

    public AuditSample(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
