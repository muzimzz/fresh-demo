package com.example.freshdemo.common.audit.repository;

import com.example.freshdemo.common.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
