package com.yamu.be.auth.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepUpAuditLogRepository extends JpaRepository<StepUpAuditLog, Long> {

    List<StepUpAuditLog> findByUserDeviceIdOrderByIdAsc(Long userDeviceId);
}
