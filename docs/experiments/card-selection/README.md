# 카드 선택 비교 실험 EXP-001~005

[종합 결론](conclusions-and-user-validation-plan.md) · [기록 점검표](record-audit.md) · [개인정보 공개 범위](privacy-review.md)

동일한 보유 카드와 거래에서 카드 선택 방법별 총혜택을 비교했다. 실사용자 후속 실험은 진행하지 않는다.

| 실험 | 조건 | 결과·기록 |
|---|---|---|
| EXP-001 | 가상 거래 24건, 카드 2장 | [보고서](exp-001-two-cards/README.md) |
| EXP-002 | 같은 거래, 카드 3장 | [보고서](exp-002-three-cards/README.md) |
| EXP-003 | 거래 금액 10·15·25·50·75% × 카드 2·3장 | [보고서](exp-003-consumption-scale/README.md) |
| EXP-004 | 기존 12조건에 혜택표 기반 수동 선택 추가 | [보고서](exp-004-manual-table/README.md) |
| EXP-005 | 실제 내역 58건, A/B 및 정책 12조건 | [개인정보를 제외한 결과](exp-005-deep-dream/execution/README.md) |

EXP-001~004의 입력·거래별 결과·한도 변화·코드 보존본은 해당 보고서와 executions/ 아래에 있다. 압축 파일은 Verify-Archives.ps1로 검증한다. 보존된 실행의 코드·정책 버전과 최신 develop은 다를 수 있으므로 최신 코드의 재실행을 원본 결과로 덮어쓰지 않는다.

EXP-005는 개인 원자료와 거래별 결과, 과거 원문 보고서, 실제 재생 코드·설정을 이 MR에서 제외했다. 집계 CSV의 수치는 원본과 같으며 공개 검증 코드는 합성 데이터만 사용한다. 공개 코드만으로 비공개 실제 사례를 재현할 수 있다고 주장하지 않는다.
