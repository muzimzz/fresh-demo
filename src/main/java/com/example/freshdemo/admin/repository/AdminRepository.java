package com.example.freshdemo.admin.repository;

import com.example.freshdemo.admin.domain.Admin;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
