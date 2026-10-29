package com.moneytransfer.transfer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaStepJpaRepository extends JpaRepository<SagaStepRecord, Long> {

    List<SagaStepRecord> findByTransferIdOrderByIdAsc(UUID transferId);
}
