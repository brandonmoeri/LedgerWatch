/** A fraud rule that fired on a transaction, as listed on the transaction itself. */
export interface TransactionFlag {
    /** The rule's name, e.g. VELOCITY. New rules can appear without a frontend release. */
    rule: string;
    detail: string;
}

/** A fraud flag as listed for an account (GET /accounts/{id}/flags). */
export interface FraudFlag extends TransactionFlag {
    id: string;
    transactionId: string;
    accountId: string;
    /** true: the transaction was rejected as SUSPECTED_FRAUD; false: posted anyway (MONITOR mode). */
    blocked: boolean;
    flaggedAt: string;
}

export interface FraudFlagsQuery {
    page?: number;
    size?: number;
}
