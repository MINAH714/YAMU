# 공개본 검증 기록

기준 브랜치: develop, 커밋 5a26209c30d826fac9de17917c8c99b6e9853d50.
작업 브랜치: test/be/S15P31E103-456-card-selection-experiments.

## 이번 MR 준비에서 수행한 검사

- 테스트 코드 전체 컴파일 성공.
- DeepDreamPolicyTest 7, DeepDreamCaseStudyTest 6, DeepDreamDeferredSettlementTest 3, DeepDreamSensitivityTest 9, DeepDreamAssumptionTest 7: 총 32개 통과, 실패·오류·생략 0.
- DeepDreamSeedTest: Testcontainers MySQL·Redis의 일회용 환경에서 1개 통과, 실패·오류·생략 0. 앞선 32개와 별도 명령으로 실행했다.
- Verify-Archives.ps1: EXP-001~004의 압축 보존 자료 199개 항목 SHA-256 일치.
- EXP-005 집계 summary.csv: 비공개 원본과 SHA-256 일치. 원본 해시 자체는 공개하지 않음.
- 공개 문서·ZIP 내부·EXP-005 합성 소스에서 개인 카드 표시 일부·개인 거래액·전월 실적·원본 명세서 해시·로컬 사용자 경로·키 패턴을 검색했고 일치 항목 없음.
- 문서의 로컬 링크 확인. 운영 코드 변경 없음.

## 실행 명령

be 폴더에서:

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.DeepDreamPolicyTest' --tests 'com.yamu.be.recommendation.DeepDreamCaseStudyTest' --tests 'com.yamu.be.recommendation.DeepDreamDeferredSettlementTest' --tests 'com.yamu.be.recommendation.DeepDreamSensitivityTest' --tests 'com.yamu.be.recommendation.DeepDreamAssumptionTest'
.\gradlew.bat test --tests 'com.yamu.be.recommendation.DeepDreamSeedTest'
```

저장소 루트에서:

```powershell
& ./docs/experiments/card-selection/Verify-Archives.ps1
```

## 수행하지 않은 작업과 한계

EXP-001~004 본 실험과 실제 58건 비교를 다시 실행하지 않았다. 전체 BE 테스트, 실제 앱 결제, 카드사 지급 대조도 수행하지 않았다. 합성 경계 검증은 실제 사용자 효과를 검증한 결과가 아니다. 보존된 과거 실험 버전과 이번 develop의 버전 차이는 각 기록에 남기며, 새 테스트 결과로 과거 수치를 덮어쓰지 않는다.

개인 정보 제거로 EXP-005 실제 재생 코드·입력·상세 결과는 공개하지 않는다. 공개 자료만으로 이 사례를 완전히 재현할 수 없다는 제한이 있다. 실사용자 후속 실험은 취소 상태다.
