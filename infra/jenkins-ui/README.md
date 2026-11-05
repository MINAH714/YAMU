# Jenkins 팀원용 결과 화면 — S15P31E103-271

기존 Pipeline Graph View와 Jenkins 기본 ListView를 사용한다. 플러그인 설치나
재시작 없이 `yamu`의 기본 화면을 Team으로 바꾸고 CI·CD·APK 탭을 제공한다.

| 화면 | 주소 |
| --- | --- |
| 전체 | <http://100.102.125.41:8081/job/yamu/view/Team/> |
| CI·MR | <http://100.102.125.41:8081/job/yamu/view/CI/> |
| BE 배포·격리 검증 | <http://100.102.125.41:8081/job/yamu/view/CD/> |
| APK | <http://100.102.125.41:8081/job/yamu/view/APK/> |

기존 Jenkins 마크업 정책을 유지한다. 현재 일반 텍스트 설정에서는 Job 역할과
`Job → 실행 번호 → Pipeline Overview` 경로를 안내한다. Safe HTML이 이미 설정된
환경에서는 최신 빌드의 `lastBuild/stages/`와 로그 링크도 표로 제공한다.
실패한 단계를 누르면 해당 로그를 확인할 수 있다. 빌드 기록이 없으면 링크를 만들지 않는다.
상태·최근 성공·실패·소요 시간은 Jenkins 목록에서 실시간으로 제공한다.
정적 설명을 현재 상태나 배포 결과로 표시하지 않는다.

이름은 실제 Job 이름 그대로 두고 역할 설명을 별도 표에 제공한다. 비활성 Job도
보여 주되 이 도구로 활성화하지 않는다. 보기용 목록에는 빌드 실행 버튼 열을 넣지 않는다.
접속 권한과 Tailscale 요구 조건은 기존과 같다.

## 설치

운영 담당자가 이 디렉터리의 `catalog.json`, `install-views.groovy`,
`remove-views.groovy`를 Jenkins home의 비공개 `.yamu-team-bootstrap`에 준비한다.
디렉터리 700, 파일 600, Jenkins UID 소유로 준비한다. 인증된 관리자 콘솔에서 실행한다.

```groovy
evaluate(new File('/var/jenkins_home/.yamu-team-bootstrap/install-views.groovy').getText('UTF-8'))
```

설치 전 기존 Job config와 승인 설정을 비교용으로 확인하고, 설치 후 그대로인지 확인한다.
설치 스크립트는 Job·자격정보·트리거·권한·마크업 정책을 저장하지 않으며 빌드를 예약하지 않는다.
공용·운영 DB 또는 컨테이너에 접근하지 않는다.

관리 표식이 없는 동명 View는 덮어쓰지 않고 전체 작업 전에 중단한다.
재실행은 관리 표식이 있는 네 View만 갱신한다. 첫 실행의 기본 View 이름을 비공개
`previous-primary-view.txt`에 남긴다. 실패 후 재실행해 부분 생성 상태를 복구할 수 있다.

되돌릴 때는 `remove-views.groovy`를 같은 방식으로 실행한다. 원래 기본 View가
존재하는지와 관리 표식을 먼저 검사하고, 이 도구가 관리하는 View만 제거한다.
Job과 빌드 기록은 삭제하지 않는다.

## 범위와 후속 작업

- 기존 `cd-verification`은 제한된 SSH 요청 1회 안에서 배포·롤백·유지 검증을 수행한다.
  실제 실행기를 바꾸지 않고 화면만 여러 배포 단계로 나누면 진행 시점을 잘못 표시한다.
  이번에는 실제 단일 단계를 그대로 연결한다.
- 배포 준비·컨테이너 교체·헬스체크·롤백의 실시간 단계 표시는 후속 실행기 계약 변경으로 다룬다.
  단계 사이 동시 실행·연결 단절·재시도·자동 롤백의 잠금과 상태 보존을 함께 설계해야 한다.
- GitLab 단계별 외부 상태 보고도 별도 후속 업무다. 이 변경은 기존 상태 보고를 건드리지 않는다.
- 새로운 배포·APK 빌드·CD 테스트 실행 없이 화면과 링크를 검증한다.

## 적용 확인 — 2026-11-03

- 실제 Jenkins 플러그인 클래스 경로에서 설치·복구 Groovy 컴파일 통과.
- 인증된 관리자 콘솔에서 설치 후 기본 화면 Team과 실제 Job 8개 표시 확인.
- 브라우저에서 CI 3개·CD 3개·APK 2개 목록과 각 탭 이동 확인.
- 적용 전후 기존 Pipeline Job 설정 파일 10개와 Jenkins 전역 설정·Script Approval
  파일 2개를 비교해 모두 동일함을 확인. 변경 범위는 `yamu`의 View와 기본 View다.
- 현재 일반 텍스트 정책을 유지해 HTML 태그가 노출되지 않는 안내를 확인.
- 복구 스크립트는 컴파일만 확인했으며 실제 View 제거는 수행하지 않았다.
