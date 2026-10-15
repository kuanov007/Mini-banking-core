package uz.kuanov.minibank.ledger;

/** Limits shared by every money-moving request. */
final class OperationLimits {

    /**
     * The largest amount a single operation may move. Far below what numeric(19,2) holds (about 10^17),
     * so one request can't push a balance anywhere near overflow.
     */
    static final String MAX_AMOUNT = "1000000000000.00";

    private OperationLimits() {
    }
}
