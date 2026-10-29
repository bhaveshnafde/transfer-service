package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.Transfer;

/** Called once a transfer is COMPLETED. Used to send the notification without blocking the response. */
public interface TransferCompletedListener {

    void onCompleted(Transfer transfer);
}
