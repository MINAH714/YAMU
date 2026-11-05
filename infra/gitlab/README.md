# GitLab에서 Jenkins 검증 결과 보기

S15P31E103-268 / Story S15P31E103-267

GitLab 외부 파이프라인 상태 API로 Jenkins 실행 중·성공·실패를 표시한다.
GitLab Runner나 `.gitlab-ci.yml`은 필요하지 않다. Jenkins 상세 로그 링크는
Tailscale에 연결된 팀원만 열 수 있다. Jenkins를 인터넷에 공개하지 않는다.

## Job과 검증 범위

| Job | 신뢰하는 제어 코드 | 검사 대상 | 실행 |
| --- | --- | --- | --- |
| `yamu/ci` | develop의 기존 Jenkinsfile | 실제 체크아웃한 develop SHA | 기존 실행 방식 유지 |
| `yamu/gitlab/mr-ci-verification` | 268 작업 브랜치 | 지정한 같은 프로젝트 MR의 소스 SHA | 최초 수동 검증 |
| `yamu/gitlab/mr-ci` | develop | develop 대상의 같은 프로젝트 열린 MR | 사람의 머지 후 활성화, 5분 간격 |

MR 검증은 고정된 소스 커밋과 `develop`의 병합 기준점 사이에서 변경 경로를 확인한다.
BE·POS·Expo·Android 경로는 해당 검사를 선택하고 여러 영역이 바뀌면 검사를 합친다.
공통 설정, 미분류 경로, 비교 실패는 기존 앱 검사 전체를 실행한다. 이름 변경은 이전 경로와 새 경로를 모두 평가한다.
격리 CD의 지정된 파일과 !104 형태의 정확한 Job 허용 목록 변경은 CD Python 컴파일과 단위 테스트를 실행한다.
그 외 격리 CD 변경은 앱 검사와 CD 검사 모두 실행한다. Job 실행과 배포 검증은 별도 절차로 수행한다.
GitLab 상태 설명에는 선택한 검사가 표시된다. 연결된 Jenkins 빌드의 `scope.json`에서 변경 경로와 생략한 검사도 볼 수 있다.

앱 전체 검증은 BE 테스트·JAR, POS lint·build, Expo TypeScript, 네이티브 Android
customer 테스트·mock APK 빌드를 실행한다. AI 테스트는 포함하지 않는다.
배포·S3 업로드·RDS 적재는 실행하지 않는다.

한 번에 가장 오래 갱신되지 않은 미검증 MR 하나를 검사한다. 현재 에이전트는
executor 1개이므로 기존 CI와 순서대로 실행되며 검증 시작까지 대기할 수 있다.
포크 MR은 제외한다. 성공한 소스 SHA라도 develop과의 병합 결과까지 검증한 것은 아니다.

## 상태의 의미와 재실행

- `jenkins/develop-ci`: 기존 CI가 실제 체크아웃한 SHA와 `develop` ref에 보고한다.
- `jenkins/mr-ci`: MR의 소스 SHA와 소스 브랜치 ref에 보고한다.
- 검사 도중 MR이 닫히거나 새 커밋이 추가되면 이전 SHA의 상태를 canceled로 마감한다.
  새 SHA에 이전 성공 결과를 옮기지 않는다.
- 같은 SHA에 이미 상태가 있으면 자동 재실행하지 않는다. 수동 재실행은
  Build with Parameters에서 `MR_IID`를 지정하고 `RETRY=true`로 실행한다.
  pending/running 상태는 수동 재실행도 중복 실행하지 않는다.
- 최종 API 보고 실패 시 Jenkins 경고와 GitLab 상태가 다를 수 있다. 담당자가 실제
  Jenkins 종료 결과를 확인하고 해당 SHA/ref에 올바른 종료 상태를 복구한 다음 재실행한다.
  상태 보고 실패를 성공으로 취급하지 않는다.
- develop 상태 전송 실패는 실제 기존 CI 결과를 덮어쓰지 않고 경고를 남긴다.

## 자격정보와 경계

GitLab 프로젝트 토큰은 `Maintainer`, scope `api`가 필요하다. 보호된 develop에서
외부 파이프라인을 생성하려면 해당 보호 브랜치에 대한 파이프라인 실행 권한이 필요하다.
보호 규칙은 변경하지 않는다. 이 구현의 API 호출은 프로젝트·MR 조회와 상태 보고로 제한한다.
승인·머지 API를 호출하지 않는다.

Jenkins `yamu` 폴더의 Secret file ID는 `yamu-gitlab-status-api`다.
기존 읽기 전용 Git credential `gitlab-yamu-read`와 분리한다. 토큰은 Git·로그·명령 인자에
넣지 않는다. 토큰 파일은 600, 디렉터리는 700으로 준비하고 AWS Secrets Manager에
암호화 백업한다. Jenkins 등록 직후 일회성 bootstrap 토큰 파일을 삭제한다.

MR 후보의 Jenkinsfile을 제어 코드로 사용하지 않는다. API credential은 선택·보고
구간에서만 바인딩하며 후보 테스트 중에는 바인딩하지 않는다. Android 도구 컨테이너에는
Docker socket·AWS 자격정보·기존 APK 서명 볼륨을 전달하지 않는다.
다만 공유 에이전트·작업 볼륨을 쓰므로 악성 코드에 대한 완전한 격리는 아니다.
팀 내부에서 신뢰한 코드에만 사용하며 외부 기여를 받기 전 별도 에이전트 격리가 필요하다.

## 설치와 활성화

1. 운영 담당자가 비공개 `.yamu-gitlab-bootstrap/api.token`과 설치 스크립트를 준비한다.
2. `verification.json`에 이 작업의 MR 번호만 `{ "iid": 123 }` 형태로 준비한다.
3. 인증된 Jenkins 관리자 스크립트 콘솔에서 실행한다.

   ```groovy
   evaluate(new File('/var/jenkins_home/.yamu-gitlab-bootstrap/install-jenkins.groovy').getText('UTF-8'))
   ```

4. 검증 Job의 Jenkins 결과와 GitLab MR 소스 SHA의 외부 파이프라인 결과를 함께 확인한다.
5. 사람이 이 MR을 develop에 머지한 뒤 `activate-jenkins.groovy`를 콘솔에서 실행한다.
   일반 MR Job을 활성화한다. develop CI의 상태 보고는 머지된 Jenkinsfile에서 적용된다.

설치 스크립트는 기존 CI Job을 수정하지 않는다. 기존 이름의 관리되지 않은 Job이나
다른 자격정보를 덮어쓰지 않는다. 재실행은 새 검증 빌드를 중복 예약하지 않는다.

## 확인 기록

2026-11-03: 상태 보고 단위 테스트 13개 통과. 실제 CI #47 FAILURE,
#48·#49 SUCCESS를 각 checkout SHA에 보고하고 GitLab API 응답을 확인했다.
Job 실행 및 MR 상태 표시 검증은 설치 후 추가 기록한다.

근거: [GitLab 외부 커밋 상태 API](https://docs.gitlab.com/api/commits/#set-commit-pipeline-status),
[보호 브랜치](https://docs.gitlab.com/user/project/repository/branches/protected/).
