-- 매월 반복의 기준일(S15P31E103-209). n번째 회차는 n달 뒤 그달의 기준일이고, 그런 날이 없는 달은 말일이다.
-- 첫 발생일만으로 계산하면 31일 반복을 말일로 당겨진 회차(11/30)에서 이후 전부 수정으로 나눌 때 기준일을 잃어
-- 새 규칙이 12/30·1/30으로 이어진다. 나눌 때 이 칸으로 기준일(31)을 이어받는다.
-- NULL이면 첫 발생일의 날짜가 기준일이다(이 칸 전에 만든 행과 매월이 아닌 반복). 그래서 기존 행은 채우지 않는다.
-- 값이 있으면 첫 발생일은 그달의 기준일(없으면 말일)이어야 한다.
ALTER TABLE cashflow_item
    ADD COLUMN recurrence_day_of_month SMALLINT NULL
        COMMENT '매월 반복 기준일(1~31), 그달에 없으면 말일; MONTHLY만, NULL이면 첫 발생일의 날짜' AFTER recurrence_end_date,
    ADD CONSTRAINT ck_cashflow_recurrence_day CHECK (
        recurrence_day_of_month IS NULL
        OR (recurrence_type = 'MONTHLY'
            AND recurrence_day_of_month BETWEEN 1 AND 31
            AND DAY(scheduled_date) = LEAST(recurrence_day_of_month, DAY(LAST_DAY(scheduled_date)))));
