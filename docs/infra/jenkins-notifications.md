# CI/CD 자동 실행과 Mattermost 알림

관련 Task: S15P31E103-109. 수신 채널은 팀 `s15p30e1`의 `야무_CI/CD`(slug `yamu_cicd`)다. Webhook URL은 이 문서나 Git에 넣지 않는다.

## 브랜치별 실행

| 대상 | 자동 실행 | 알림 |
|---|---|---|
| develop → `yamu/ci` | 5분 SCM polling, 기존 전체 CI 유지 | 성공·실패·unstable·중단 |
| master → `yamu/cd` | 활성화와 최초 실행 이후 5분 SCM polling | 승인 대기·성공·실패·중단 |
| `yamu/ci-master` | CD가 호출하며 별도 폴링 없음 | 별도 전송 없음; 결과는 부모 CD에 반영 |

develop에서는 CD를 실행하거나 CD 알림을 보내지 않는다. master의 CD는 전체 릴리스 CI를 통과한 후 `yamu` 승인을 최대 30분 기다린다. 승인 전에는 BE를 교체하지 않는다. POS·모바일·AI 배포와 변경 경로별 선택 검사는 이번 범위가 아니다.

SCM 폴링은 머지 전용 이벤트가 아니라 해당 브랜치의 변경을 감지한다. protected branch와 MR 리뷰 규칙을 유지한다. 폴링 간격 내 여러 머지는 최신 커밋 하나로 합쳐질 수 있으며 모든 중간 커밋의 개별 배포를 보장하지 않는다. 승인 중 master가 바뀌면 호스트 배포 도구가 오래된 후보를 거부한다. 동시 실행 제한과 호스트 잠금도 유지한다.

## 비밀값 등록

1. Mattermost에서 `야무_CI/CD` 채널용 Incoming Webhook을 만들고 **Lock to this channel**을 선택한다. 채널 고정을 Mattermost 설정에서 확인한다.
2. Jenkins → yamu → Credentials → yamu Folder/Global → Add Credentials → Secret text.
3. ID는 `yamu-mattermost-webhook`, Secret은 HTTPS Incoming Webhook URL 전체로 등록한다. Description은 `YAMU CI/CD Mattermost`다. 비밀값은 사용자가 직접 입력·저장한다.
4. CI/CD Job·저장소 코드·Credentials 편집 권한이 있는 사람은 알림 전송 권한이 있는 것으로 취급한다. 일반 로그인 사용자에게 이 권한을 부여하지 않는다.

추가 Mattermost 플러그인은 필요 없다. 기존 Credentials Binding과 에이전트의 Python 표준 라이브러리를 사용한다. Webhook의 기본 채널을 사용하며 payload로 다른 채널을 지정하지 않는다.

## 전송 동작과 한계

- 알림에는 CI/CD 구분, 결과, Job·빌드 번호, 커밋, 빌드 링크와 CD 후보 번호를 넣는다. 커밋 메시지·콘솔 로그·환경변수 전체·SSH 키·DB 인증은 넣지 않는다. 알림의 빌드 링크에는 Jenkins Tailscale 접속이 필요하다.
- HTTPS `/hooks/...` URL만 허용하며 TLS 인증서를 검증한다. 리다이렉트를 따라가지 않고 HTTP 요청은 8초 제한을 둔다. 성공은 HTTP 200과 `ok` 응답으로 확인한다.
- 타임아웃은 수신 여부가 불명일 수 있으므로 HTTP 요청을 재전송하지 않는다. 실패는 URL·예외 내용·응답 본문 없이 경고만 남기고 기존 빌드 결과를 바꾸지 않는다.
- CD 알림은 준비 단계에서 고정한 도우미 두 파일을 stash해 사용한다. 승인 대기 중에는 CI 에이전트를 점유하지 않는다. 알림의 에이전트 배정·실행은 1분 제한이다. Jenkins retry의 중단 구분을 사용하되, 두 번째 통과는 경고만 남기고 실제 전송은 반복하지 않는다. 사용자의 Build Abort는 계속 전파한다.
- 최초 checkout/stash 자체가 실패하면 도우미가 없어 알림을 보낼 수 없으며 Jenkins에 경고를 남긴다. Jenkins나 에이전트가 완전히 중단된 상황의 외부 감시는 별도다.
- 운영 배포 실패 알림만으로 복구 성공을 단정하지 않는다. CD 로그와 health에서 실제 복구 결과를 확인한다.

## 적용 순서

현재 master는 초기 커밋이므로 운영 CD Job을 활성화하지 않는다. 코드·알림 검증 후 develop 대상 MR을 리뷰하고, 사람이 필요한 코드를 master에 릴리스한다. 그 뒤 master의 Jenkinsfile·알림 도우미·환경을 대조하고 ci-master와 cd를 활성화해 첫 실행을 수행한다. 승인 대기에서 후보를 확인한 후 사람이 배포를 승인한다. AI는 MR을 승인하거나 Merge하지 않는다.

## 검증

- `python -m unittest discover -s infra/ci -p 'test_*.py'`: 결과 게이트와 알림 테스트. 알림 테스트는 payload 정보 제한, 멘션 방지, HTTP 제한·리다이렉트 거부, 전송 실패·비밀값 미노출을 확인한다.
- 설치된 Jenkins Declarative 파서로 Jenkinsfile 두 개와 비활성 master-CI의 빈 폴링 스케줄을 검증한다.
- 별도 `yamu/notifications-verification`에서 실제 에이전트·Credentials Binding·CI 알림·CD stash/알림 경로를 검증한다. 메시지는 TEST이며 배포가 없음을 표시한다. 검증 후 해당 Job은 비활성으로 보관한다.

### 2026-10-27 검증 결과

사용자가 폴더 범위 Secret text를 직접 등록했다. 로컬 CI 관련 8개·CD 검증 4개 테스트를 통과했다. 처음 로컬 CI 테스트는 Windows 샌드박스 임시 폴더 권한으로 실패해, 정상 권한으로 같은 검사를 다시 실행한 뒤 통과했다.

[검증 빌드 #1](http://100.102.125.41:8081/job/yamu/job/notifications-verification/1/console)에서 실제 CI·CD 테스트 알림 두 건 모두 HTTP 200/ok를 확인했으나, 후속 중단 구분 API가 Jenkins 샌드박스에 차단돼 빌드는 실패했다. 추가 시그니처 권한을 승인하지 않고 기본 retry 단계의 중단 처리를 사용하도록 수정했으며, 불필요해진 승인 요청도 제거했다.

[검증 빌드 #2](http://100.102.125.41:8081/job/yamu/job/notifications-verification/2/console)는 SUCCESS다. Linux 에이전트의 알림 테스트 6개, 실제 CD TEST 알림 응답, stash 복원, 빈 폴링 스케줄의 실제 적용을 확인했다. 1초 타임아웃의 두 번째 통과에서는 전송하지 않으며, 검증 입력의 Abort는 retry를 반복하지 않고 전파됨을 확인했다. 테스트 알림은 총 세 건 전송했다. 채널 고정 여부는 Mattermost 측 설정에서 확인해야 하며 Jenkins는 채널을 덮어쓰지 않는다.

검증 Job은 비활성으로 보관했다. 기존 develop CI는 활성, 에이전트는 Online, master CI와 CD는 비활성 상태를 유지한다. 자동 알림·트리거 코드는 MR을 통해 develop에 반영하고, CD 적용은 이후 사람이 master 릴리스와 최초 실행을 완료해야 한다. 이번 검증은 실제 운영 배포 성공을 의미하지 않는다.

참고: [Jenkins Pipeline 문법](https://www.jenkins.io/doc/book/pipeline/syntax/), [Mattermost Incoming Webhooks](https://docs.mattermost.com/integrations-guide/incoming-webhooks).
