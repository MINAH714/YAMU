-- Explicit confirmation evidence. NULL retains the existing pending behavior.
-- QA_HISTORY is a scenario record, not an external payout or bank transaction.
ALTER TABLE payment_benefit
  ADD COLUMN confirmed_at DATETIME(6) NULL,
  ADD COLUMN confirmation_source VARCHAR(32) NULL,
  ADD CONSTRAINT ck_benefit_confirmation_pair CHECK (
    (confirmed_at IS NULL AND confirmation_source IS NULL)
    OR (confirmed_at IS NOT NULL AND confirmation_source IS NOT NULL AND confirmation_source = 'QA_HISTORY'));
