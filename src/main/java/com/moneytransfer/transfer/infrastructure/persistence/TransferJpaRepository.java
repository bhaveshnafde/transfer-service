package com.moneytransfer.transfer.infrastructure.persistence;

import com.moneytransfer.transfer.domain.NotificationStatus;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferJpaRepository extends JpaRepository<Transfer, UUID> {

    @Query("select t from Transfer t where t.status in :statuses and t.updatedAt < :before order by t.updatedAt")
    List<Transfer> findStuck(@Param("statuses") Collection<TransferStatus> statuses,
                             @Param("before") Instant before,
                             Pageable page);

    /**
     * Updates only the notification column. It does not bump the version, because the notification
     * is a side channel that runs after the transfer is final.
     */
    @Transactional
    @Modifying
    @Query("update Transfer t set t.notificationStatus = :status where t.id = :id")
    int updateNotificationStatus(@Param("id") UUID id, @Param("status") NotificationStatus status);
}
