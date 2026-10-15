package uz.kuanov.minibank.ledger;

/** Who asked, under which key, for what: everything needed to replay a request or reject a reused key. */
record IdempotentRequest(long initiatorId, IdempotencyKey key, String requestHash) {
}
