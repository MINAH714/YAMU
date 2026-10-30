-- 카드사가 정한 출금·결제 계좌(10/30 결정, S15P31E103-187).
-- 실제 카드는 카드사가 카드마다 출금 계좌 하나를 정해 둔다(신용카드 결제계좌, 체크카드 연결계좌). YAMU에서는 시드가 카드사 역할로 적는다.
-- 시드를 넣을 때는 아직 linked_account 행이 없어(계좌는 가입 중 목록에서 골라 연결) linked_account_id로 가리킬 수 없다.
-- 그래서 계좌 자체(은행 코드 + 조회 해시 + 끝 4자리)를 적어 두고, 카드 등록 때 사용자가 연결한 계좌와 대조해 linked_account_id를 채운다.
-- 등록 뒤 출금 계좌의 기준은 linked_account_id다(카드 관리에서 결제계좌를 바꾸면 그쪽만 바뀐다). 이 칸은 등록 때만 쓴다.
-- 해시는 linked_account.account_no_hash와 같은 keyed HMAC(은행 코드 + 정규화 계좌번호)이라 바로 대조할 수 있다.
-- NULL 허용: 계좌를 정하지 않은 기존 시드 행(예: 처음부터 ACTIVE로 넣는 카드)도 그대로 들어가게 한다. 세 칸은 함께 있거나 함께 없다.
-- 버전 번호: 이 MR(!47)이 위에 쌓인 !48(V20261029_1)·!49(V20261029_2)보다 먼저 머지되므로 그 앞 번호를 쓴다.
ALTER TABLE user_card
    ADD COLUMN issuer_account_bank_code VARCHAR(20) CHARACTER SET ascii NULL
        COMMENT '카드사(시드)가 정한 출금·결제 계좌의 금융망 은행 코드; 등록 때 연결 계좌와 대조' AFTER linked_account_id,
    ADD COLUMN issuer_account_hash BINARY(32) NULL
        COMMENT '카드사가 정한 계좌의 조회용 keyed HMAC(linked_account.account_no_hash와 같은 방식); 원문 저장 안 함' AFTER issuer_account_bank_code,
    ADD COLUMN issuer_account_last4 CHAR(4) CHARACTER SET ascii NULL
        COMMENT '카드사가 정한 계좌번호 끝 4자리(화면 표시용)' AFTER issuer_account_hash,
    ADD CONSTRAINT ck_card_issuer_account CHECK (
        (issuer_account_bank_code IS NULL AND issuer_account_hash IS NULL AND issuer_account_last4 IS NULL)
        OR (issuer_account_bank_code IS NOT NULL AND issuer_account_hash IS NOT NULL AND issuer_account_last4 IS NOT NULL));
