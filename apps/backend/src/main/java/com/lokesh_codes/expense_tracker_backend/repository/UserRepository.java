package com.lokesh_codes.expense_tracker_backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;

public interface UserRepository extends JpaRepository<User, Integer>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByRole(Role role);

    long countByRole(Role role);

    long countByActive(boolean active);

    long countByCreatedAtAfter(Instant since);

    long countByLastLoginAtAfter(Instant since);
}
