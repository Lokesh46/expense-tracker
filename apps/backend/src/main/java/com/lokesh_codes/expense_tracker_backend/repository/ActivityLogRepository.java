package com.lokesh_codes.expense_tracker_backend.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lokesh_codes.expense_tracker_backend.entity.ActivityAction;
import com.lokesh_codes.expense_tracker_backend.entity.ActivityLog;

public interface ActivityLogRepository
        extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {

    List<ActivityLog> findByUsernameOrderByOccurredAtDesc(String username, Pageable pageable);

    long countByActionAndOccurredAtAfter(ActivityAction action, Instant since);

    /**
     * Trims the log to a retention window.
     *
     * <p>A table that only grows will, on a free-tier database, eventually be the
     * largest thing in it. Deleted in bulk rather than by loading entities,
     * because there is nothing to inspect on the way out.
     */
    @Modifying
    @Query("delete from ActivityLog a where a.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
