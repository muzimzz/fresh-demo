package com.example.freshdemo.membergrade.repository;

import com.example.freshdemo.membergrade.domain.MemberGrade;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberGradeRepository extends JpaRepository<MemberGrade, Long> {

    Optional<MemberGrade> findByIsDefaultTrue();

    boolean existsByName(String name);
}
