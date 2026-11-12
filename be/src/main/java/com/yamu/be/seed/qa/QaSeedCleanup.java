package com.yamu.be.seed.qa;

import org.springframework.jdbc.core.JdbcTemplate;
import static com.yamu.be.seed.qa.QaSeedSpec.require;

/** Delete all data owned by a registered QA user, including rows created during QA. FK checks stay on. */
final class QaSeedCleanup {
    private final JdbcTemplate db;
    QaSeedCleanup(JdbcTemplate db) { this.db = db; }
    void remove(long user) {
        require(db.queryForObject("SELECT COUNT(*) FROM qa_seed_member WHERE user_id=?", Long.class, user) == 1,
                "User is not registered QA ownership");
        db.queryForObject("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE", Long.class, user);
        require(db.queryForObject("""
                SELECT COUNT(*) FROM payment_transaction WHERE user_id=?
                AND (status IN ('REQUESTED','PROCESSING') OR (fin_request_no IS NOT NULL AND fin_transaction_no IS NULL))
                """, Long.class, user) == 0, "Resolve in-flight/unknown payments first");
        require(db.queryForObject("""
                SELECT COUNT(*) FROM card_bill WHERE user_id=? AND fin_request_no IS NOT NULL
                AND fin_transaction_no IS NULL AND status NOT IN ('UNPAID','CANCELED')
                """, Long.class, user) == 0, "Resolve unknown bill withdrawals first");
        require(db.queryForObject("""
                SELECT COUNT(*) FROM refund WHERE user_id=? AND fin_request_no IS NOT NULL
                AND fin_transaction_no IS NULL AND status <> 'FAILED'
                """, Long.class, user) == 0, "Resolve unknown refunds first");
        // bill_item_discount has independent FKs, not a composite owner constraint.
        require(db.queryForObject("""
                SELECT COUNT(*) FROM bill_item_discount d
                JOIN card_bill b ON b.card_bill_id=d.card_bill_id
                JOIN bill_item i ON i.bill_item_id=d.bill_item_id
                JOIN payment_benefit pb ON pb.payment_benefit_id=d.payment_benefit_id
                JOIN payment_transaction p ON p.payment_transaction_id=pb.payment_transaction_id
                WHERE (b.user_id=? OR i.user_id=? OR p.user_id=?)
                AND NOT (b.user_id=? AND i.user_id=? AND p.user_id=?)
                """, Long.class, user, user, user, user, user, user) == 0, "Cross-owner discount reference");
        // Application references without an FK must not be orphaned across owners.
        for (String[] target : new String[][]{
                {"PAYMENT_TRANSACTION", "payment_transaction", "payment_transaction_id"},
                {"USER_CARD", "user_card", "user_card_id"},
                {"CARD_BILL", "card_bill", "card_bill_id"},
                {"CASHFLOW_ITEM", "cashflow_item", "cashflow_item_id"},
                {"REFUND", "refund", "refund_id"}}) {
            require(db.queryForObject("SELECT COUNT(*) FROM notification n JOIN " + target[1]
                    + " t ON t." + target[2] + "=n.target_id WHERE n.target_type=? AND t.user_id=? AND n.user_id<>?",
                    Long.class, target[0], user, user) == 0, "Cross-owner notification reference");
        }
        require(db.queryForObject("""
                SELECT COUNT(*) FROM cashflow_create_request r JOIN cashflow_item i USING(cashflow_item_id)
                WHERE i.user_id=? AND r.user_id<>?
                """, Long.class, user, user) == 0, "Cross-owner request reference");
        db.update("""
                DELETE d FROM bill_item_discount d JOIN card_bill b USING(card_bill_id) WHERE b.user_id=?
                """, user);
        for (String table : new String[]{"refund", "cashflow_payment_match", "cashflow_create_request",
                "living_cost_prediction", "credit_spending_prediction", "cashflow_occurrence"}) {
            db.update("DELETE FROM " + table + " WHERE user_id=?", user);
        }
        db.update("UPDATE cashflow_item SET previous_cashflow_item_id=NULL WHERE user_id=?", user);
        for (String table : new String[]{"cashflow_item", "bill_item", "card_bill", "notification"}) {
            db.update("DELETE FROM " + table + " WHERE user_id=?", user);
        }
        for (String table : new String[]{"payment_limit_usage", "payment_benefit"}) {
            db.update("DELETE x FROM " + table
                    + " x JOIN payment_transaction t USING(payment_transaction_id) WHERE t.user_id=?", user);
        }
        db.update("DELETE FROM payment_transaction WHERE user_id=?", user);
        for (String table : new String[]{"card_monthly_performance", "monthly_limit_usage"}) {
            db.update("DELETE x FROM " + table + " x JOIN user_card c USING(user_card_id) WHERE c.user_id=?", user);
        }
        db.update("DELETE FROM user_card WHERE user_id=?", user);
        db.update("DELETE FROM linked_account WHERE user_id=?", user);
        db.update("DELETE FROM step_up_audit_log WHERE user_id=?", user);
        db.update("DELETE e FROM user_device_event e JOIN user_device d USING(user_device_id) WHERE d.user_id=?", user);
        db.update("DELETE FROM user_device WHERE user_id=?", user);
        db.update("DELETE FROM qa_seed_member WHERE user_id=?", user);
        db.update("DELETE FROM app_user WHERE user_id=?", user);
        // New unsupported FK dependents fail closed and roll back the complete dataset transaction.
    }
}
