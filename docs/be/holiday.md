# 영업일 계산 (holiday)

청구 출금일, 할부 회차 출금일, 환급 기한, 잔고예보의 가상 청구 일정이 같은 규칙으로 영업일을 세도록 공통 계산기를 둡니다(`com.yamu.be.global.calendar`).
기준은 결제 정책 초안 4-3(POL-005·FR-067)과 10/21 결제 정책 회의입니다.

## 쓰는 법

`BusinessDayCalendar` 빈을 주입받아 씁니다. 날짜는 모두 **한국 날짜**(`LocalDate`)입니다. UTC 시각에서 날짜를 뽑을 때는 `ZoneId.of("Asia/Seoul")`로 바꾼 뒤 넘깁니다.

| 메서드 | 결과 | 예 |
|---|---|---|
| `isBusinessDay(date)` | 토·일·공휴일이 아니면 true | 2026-10-09(금, 한글날) → false |
| `businessDayOnOrAfter(date)` | 영업일이면 그날, 아니면 그 뒤 첫 영업일 | 2026-09-24(추석 연휴) → 2026-09-28(월) |
| `plusBusinessDays(date, n)` | 기준일은 세지 않고 다음 날부터 영업일을 n개 센 날. n은 1~366 | 2026-12-30(수) + 3 → 2027-01-05(화) |

```java
LocalDate withdrawalDate = calendar.businessDayOnOrAfter(paymentDate);   // 청구 출금일
LocalDate refundDueDate = calendar.plusBusinessDays(canceledOn, 3);      // 환급 기한
```

## 규칙

- **쉬는 날:** 토요일, 일요일, 공휴일(대체공휴일·선거일 포함)
- **노동절·제헌절:** 2026년부터 관공서 공휴일이라 쉬는 날입니다.
- **임시(1차):** 12월 31일처럼 관공서 공휴일이 아닌 날은 영업일로 셉니다.
- **데이터가 없는 해:** 주말만 쉬는 날로 계산하고, 경고 로그를 해마다 한 번 남깁니다.
- **데이터 파일이 깨졌을 때:** 서버가 뜨지 않습니다. 출금일이 틀린 채로 계산되는 것보다 바로 드러나는 편이 낫기 때문입니다.

## 공휴일 데이터

- `be/src/main/resources/holidays/{연도}.json`에 2026·2027년이 있습니다. 출처는 각 파일의 `source`에 적혀 있습니다.
- 공공데이터 API 동기화(5.4.6, S15P31E103-211)가 붙기 전까지는 공휴일이 바뀌면 이 파일을 직접 고칩니다. 고칠 때는 `EmbeddedHolidaysTest`의 합계 확인도 같이 고칩니다.
