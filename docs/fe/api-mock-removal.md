## 🛠️ 작업 파트

- [x] FE (Frontend)
- [ ] BE (Backend)
- [ ] AI
- [ ] Data
- [ ] Infra

## 📌 연관된 Jira 이슈

[S15P31E103-561](https://ssafy.atlassian.net/browse/S15P31E103-561) — 김경린 / 6주차 스프린트 개발 / 4pt.

Task 560 및 MR !385와 분리한 전용 작업이다. develop 17fa14fc에서 분기했고, !385 병합 후 최신 develop c6cadd52를 반영했다. README·Debug DataModule·ApiFeatureTest 충돌을 해결했으며 실제 API 전용 연결과 !385의 리포트·QA 이력 검증을 모두 유지했다.

## 📝 작업 내용 요약

Debug에서도 고객 앱과 POS의 저장소를 기존 실제 API 구현에 직접 연결한다. 서버에 거절 API가 있는데도 성공만 돌려주던 RealPaymentWithFakes와 가계부만 실제이고 예보·구매 계획은 가짜였던 RealCashflowWithFakes를 제거했다. 별도 가짜 POS 서버도 제거했다.

기존 yamu.realApi 부분 설정 또는 빈 설정으로 MOCK이 다시 선택되지 않도록 Debug의 실제 API 목록을 전체로 고정했다. 로그인·홈·카드·계좌·소비 리포트·실적·예보·가계부·청구·알림·POS는 기존 실제 저장소를 사용한다. 서버 오류나 미구현 API는 기존 오류 처리로 전달한다.

수정 범위는 DI 연결·빌드 전환 설정·사용하지 않는 MOCK 우회 구현 제거와 테스트/문서다. 실제 Repository 구현, HTTP 요청 본문, 화면, 잔액·예보·혜택 계산, BE 및 DB는 수정하지 않았다. 기존 단위 테스트에 쓰는 Fake 클래스는 보존했으나 앱 저장소로 주입하지 않는다.

## 📸 테스트 결과

최신 develop 반영 및 충돌 해결 후 고객 앱 1,299개·POS 1,338개 전체 단위 테스트 통과. 실패·오류·건너뜀 0건. Hilt 연결 컴파일 확인 후 테스트 작업을 --rerun으로 새로 실행해 BUILD SUCCESSFUL(34초)을 확인했다.

```powershell
# fe/android에서 ANDROID_HOME을 설치된 SDK 경로로 지정
.\gradlew.bat :app:testCustomerDebugUnitTest --rerun :app:testPosDebugUnitTest --rerun --parallel --console=plain
```

추가 테스트는 모든 기능의 실제 API 활성 상태, 결제 거절 POST 경로·멱등 키 전달, 서버 오류를 성공으로 바꾸지 않는 처리를 확인한다. 기존 자산 흐름 SIMULATED 표시 검증도 전체 회귀에 포함한다. 실제 서버 금융 거래·QA 재적재·실기기 설치는 실행하지 않았다. 2026-11-16 실제 서버 https://yamu.it.kr/api/health 의 status=ok와 accounts·asset-flows·reports/monthly의 미인증 HTTP 401 응답을 확인했다. 인증된 계정으로 실제 계좌·잔액·QA 이력이 조회되는지는 미검증이다. 401만으로 해당 기능의 정상 동작이나 데이터 적재를 입증할 수 없다.

## 💬 리뷰 요구사항

Debug도 실제 서버 주소(yamu.baseUrl), 로그인 계정, POS는 yamu.posKey가 필요하다. 기존 설정을 읽어 가짜 저장소로 대체하는 동작은 없다. 서버 주소·키·사용자 데이터는 이 MR에 포함하지 않았다.

## ✅ MR 체크리스트

- [x] Task 전용 브랜치·커밋·MR 제목 규칙 준수
- [x] 담당자 김경린·활성 스프린트·진행 중 상태 설정
- [x] 최종 자동 테스트 결과 확인
- [ ] 사람의 리뷰 승인 및 MR 병합 후 Jira 완료 처리
