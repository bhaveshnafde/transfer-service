package com.moneytransfer.transfer.infrastructure.persistence;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.saga.TransferStore;
import org.springframework.stereotype.Component;

/**
 * Each save is its own short transaction (Spring Data's default). Because the transfer carries a
 * @Version, saving a stale copy fails with an optimistic-lock error instead of overwriting newer state.
 */
@Component
public class JpaTransferStore implements TransferStore {

    private final TransferJpaRepository repository;

    public JpaTransferStore(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Transfer save(Transfer transfer) {
        return repository.save(transfer);
    }
}
