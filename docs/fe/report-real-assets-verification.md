## 🛠️ 작업 파트

- [x] FE (Frontend)
- [ ] BE (Backend)
- [ ] AI
- [ ] Data
- [ ] Infra

## 📌 연관된 Jira 이슈

[S15P31E103-560](https://ssafy.atlassian.net/browse/S15P31E103-560) — 정민아, 6주차 스프린트 개발, 4pt. 선행 계좌 이력 저장 MR !360은 develop에 병합된 상태다.

## 📝 작업 내용 요약

Debug에서 report만 실제 API로 활성화하면 소비 통계는 서버 응답인데 같은 화면의 자산 흐름은 가짜 저장소에 남았다. 이제 report를 켜면 forecast와 link도 함께 활성화하여 소비 리포트의 계좌·현재 잔액·월별 자산 흐름과 잔고예보가 같은 실제 API 경로를 사용한다.

실서버 시험 빌드는 local.properties에 yamu.realApi=auth,report와 대상 yamu.baseUrl을 지정하고 다시 빌드한다. all과 release도 실제 API를 사용한다. 빈 설정의 debug는 명시적인 화면 개발용 가짜 모드이며, 이번 작업에서 사용자 기기의 설치 앱이나 비공개 서버 설정을 변경하지 않았다.

QA 합성 이력은 서버의 SIMULATED 및 displayBalance를 읽으며 실제 과거 잔고와 구분해 QA 합성으로 표시한다. API 오류를 FakeAssetFlowRepository로 대체하지 않는다.

## 📸 테스트 결과

2026-11-16 develop 17fa14fc 기반에서 Android 6개 클래스, 100개 테스트 통과. 실패·오류·건너뜀 0건. 앱/Hilt/테스트 코드 컴파일 포함 BUILD SUCCESSFUL.

```powershell
# fe/android에서 설치된 Android SDK를 ANDROID_HOME으로 지정한 뒤 실행
.\gradlew.bat :app:testCustomerDebugUnitTest --tests '*ApiFeatureTest' --tests '*AssetFlow*' --tests '*ReportMonthViewModelTest' --tests '*ForecastChart*' --tests '*ForecastDaySync*' --console=plain
```

- report 활성화 시 forecast·link까지 활성화되는 의존 설정 검증.
- MockWebServer의 HTTP JSON을 실제 Retrofit 저장소에 전달하여 /accounts 응답의 기준 계좌로 /forecasts/balance를 호출하고 서버 잔액을 사용하는지 검증.
- /asset-flows의 QA 합성 0원(actualBalance=null)과 다음 날 실제 잔고를 역직렬화해 그래프 실선 값·날짜 칸 금액·QA 안내·접근성 문구까지 검증.
- 계좌 조회 실패를 가짜 계좌로 메우지 않고 실패로 반환하는지 및 기존 월 전환·예보·자산 흐름 회귀 검증.

실서버 QA 계정 데이터나 실기기 화면을 관측한 결과는 아니다. DB 적재·API 배포·실기기 설치는 하지 않았다. 서버에 QA 이력이 적재되고 경계 검증이 유효해야 해당 구간이 표시된다. 이 MR은 앱 경로 전환과 응답 처리에 대한 검증 결과다.

## 💬 리뷰 요구사항

report 활성화 시 잔고예보 화면도 실제 API로 전환되는 것이 의도된 동작이다. 같은 계좌의 잔액과 예보가 화면마다 다른 출처를 사용하지 않도록 맞췄다. 완전한 실서버 시험에는 실제 로그인 및 대상 API 서버 설정이 필요하다.

## ✅ MR 체크리스트

- [x] 커밋 메시지 및 MR 제목 컨벤션 준수
- [x] 로컬 자동 테스트 통과
- [x] Jira 담당자·활성 스프린트·스토리 포인트·진행 중 상태 반영
- [ ] 사람의 리뷰 승인 및 MR 병합 후 Jira 완료 처리
