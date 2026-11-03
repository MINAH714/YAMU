# 격리 CD 실시간 단계 — S15P31E103-273

한 번의 SSH 요청과 호스트 잠금을 유지하면서 실제 작업의 시작·완료 이벤트를
Jenkins Pipeline Graph View에 표시한다. 완료 후 결과를 여러 가짜 단계로 재생하지 않는다.

## 실행 계약

- 고정 대상: `/opt/yamu/cd-verification-20261030`, Compose `yamu-cd-verify-20261029`.
- 고정 DB: 격리 Compose 내부 `db:3306/yamu_cd_verify`. 외부 DB·RDS·호스트 포트를 거절한다.
- 요청: `stages:<CI 번호>:cached|ci:normal|fail-health:<32자리 hex 요청 ID>`.
  명령·경로·이미지·호스트·환경변수는 호출자가 지정할 수 없다.
- 기존 `verify:<CI 번호>` 요청은 기존 실행기로 그대로 처리한다.
- `request.lock`, 기존 검증 잠금, `test.lock`을 실행·복구 전체에 걸쳐 보유한다.
  동시 요청은 대기·중복 실행하지 않고 실패한다. 이미 사용한 요청 ID도 거절한다.
  새 요청 ID는 새로운 명시적 검증 실행이다. 결과 불명 실행을 자동 재시도하지 않는다.
- 기존 검증 호스트의 root 소유 `verify.py`, `prepare-candidate.py`를 신뢰하는 도구로 사용한다.
  `ci` 모드는 보관된 CI 성공·JAR 체크섬·마이그레이션 일치를 기존 후보 준비기로 확인한다.
  기본 `cached` 모드는 root 소유 metadata의 고정 빌드 번호·캐시 JAR 체크섬·기준 JAR과의
  마이그레이션 일치·이미지 ID 및 소스 revision 라벨을 대조한다. CI 21의 Jenkins 기록은
  보관 기간 만료로 삭제되어 이 모드를 사용한다. 새 CI 성공을 재확인한 것으로 표시하지 않으며
  최종 결과에 `artifact_source=cached_fixture`를 명시한다.

## 표시 단계

대상·후보 확인 → 이미지 준비 → 기준 상태·유지 표식 → 컨테이너 교체·Docker 대기 →
API·DB·Redis 헬스체크 → 정상 버전 기록 → 수동 롤백 → 불건전 후보 거절·자동 복구 →
DB·Redis 유지 → 최종 후보 재배포 → 최종 상태 확인.

Jenkins에는 원격 실행과 이벤트 관찰 두 개의 병렬 분기가 있다. 원격 실행은 하나이며
별도 백그라운드 데몬을 만들지 않는다. 단계는 호스트의 시작 이벤트를 관측한 뒤 생성하고
완료 이벤트까지 기다린다. 폴링·스케줄 지연 때문에 화면 소요 시간과 호스트 시간이
다를 수 있으며, 실제 시각·순서는 `events.jsonl`에 보존한다.

모든 단계와 격리 환경·건강·데이터 유지 최종 증거가 있어야 성공한다. 이벤트 ID·순서·
크기를 검증하며 누락·중복·역순·EOF·SSH 실패를 성공으로 바꾸지 않는다.
실패 단계는 빨간색으로 남기고 이전 버전 복구가 관측되면 별도의 복구 단계로 보여 준다.

`fail-health`는 실제 후보의 정상 헬스체크 직후 의도적으로 실패한다. 이전 이미지를
복구하고 건강 확인·설정 저장까지 검증한다. **이 테스트의 Jenkins 최종 FAILURE는
정상적인 기대값**이며, 복구 성공을 전체 성공으로 바꾸지 않는다.

## 중단과 한계

SSH 단절 시 호스트는 SIGHUP을 무시하고 잠금을 유지한 채 검증·복구를 끝낸다.
SIGTERM·SIGINT·12분 제한은 복구를 시도한다. 복구 중에는 추가 종료 신호를 무시한다.
클라이언트는 중단을 실패로 기록하며 서버의 복구를 확인하지 못한 상태를 성공으로
표시하지 않는다. 운영자는 root 전용 `stage-requests/<ID>.jsonl`과 실제 건강을 확인한다.

SIGKILL, 호스트 장애, Docker 데몬 장애는 코드로 복구를 보장할 수 없다. 이 경우 수동
확인이 필요하며 새 실행도 건강·잠금·대상 검증을 먼저 통과해야 한다.
관측 출력이 끊겨도 복구를 막지 않으며, 단계 기록 디스크 오류가 나도 복구를 시도한다.

## 설치·검증

1. 운영자가 검토한 파일들을 root 소유·다른 사용자 쓰기 불가인 staging에 준비하고
   `sudo python3 install-host.py`로 `host.py`, `protocol.py`를 고정 호스트 디렉터리에 설치한다.
   `host-baseline.json`은 검토한 기존 설치본 코드 버전의 체크섬이며 키·설정 값이 아니다.
   기존 강제 SSH 엔트리에는 검증된 `stages:` 요청에 대한 고정 경로 분기만 추가한다.
   기존 파일 해시 확인·원본 백업 후 원자적으로 교체한다. 자격정보와 SSH 권한은 유지한다.
2. `install-jenkins.groovy`를 Jenkins 비공개 `.yamu-cd-stages-bootstrap`에 준비한다.
3. 인증된 관리자 콘솔에서 실행한다.

   ```groovy
   evaluate(new File('/var/jenkins_home/.yamu-cd-stages-bootstrap/install-jenkins.groovy').getText('UTF-8'))
   ```

4. `yamu/cd-stages-verification`에서 `normal`, `fail-health`를 각각 실행한다.
   기본 SOURCE=cached, CI 21은 기존 격리 스키마와 같은 고정 검증 자료다. 최신 CI가 스키마와 같다고
   가정하지 않는다. 테스트는 운영·공용 개발 DB 또는 해당 컨테이너를 변경하지 않는다.
5. 기존 `yamu/cd-verification`과 운영 CD Job은 그대로 둔다. 머지 후 기존 검증 Job으로
   적용하는 작업은 별도로 검토한다. AI가 승인·머지하지 않는다.

로컬 검사: `python -m unittest discover -s infra/cd-stages -p "test_*.py"`.
진행 자료는 Jenkins `stage-status/*.json`, `*.jsonl`에 공개 검증 정보만 보관한다.
키 파일·환경변수·Docker 원문 오류·컨테이너 설정은 출력하거나 보관하지 않는다.
