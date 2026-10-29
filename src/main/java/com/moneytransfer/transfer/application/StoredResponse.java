package com.moneytransfer.transfer.application;

import java.util.UUID;

/**
 * An HTTP status and the exact JSON text returned to the client. The same text is stored with the
 * Idempotency-Key, so a replay is byte-for-byte identical to the original response.
 */
public record StoredResponse(int status, String json, UUID transferId) { }
