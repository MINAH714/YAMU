# 목데이터 Android APK 자동 빌드·업로드 — S15P31E103-263

`fe/android`의 기존 `customerDebug`를 별도 Jenkins Job으로 빌드한다. Expo 및 기존 BE CI는 변경하지 않는다.
앱의 화면·목데이터·Gradle 변형도 그대로 사용한다. Android 11 이상용 고객 앱이며 POS APK·release·실제 API 연동은 이 Job의 대상이 아니다.

## Job과 트리거

| Job | 소스 | 실행 |
|---|---|---|
| `yamu/mobile/apk-mock-verification` | 263번 작업 브랜치 | 관리자 설치 후 최초 1회, 이후 수동. SCM 자동 폴링 없음 |
| `yamu/mobile/apk-mock` | `develop` | MR 머지·검증 Job 성공 후 활성화. 5분 폴링, `fe/android/**`·`infra/android/**` 변경만 대상 |

각 Job은 동시 실행을 막고 60분 뒤 중단한다. 기존 `yamu-ci` 라벨의 실행기 1개를 사용해 업무 CI와 동시에 과도한 빌드를 실행하지 않는다.
`disableConcurrentBuilds`와 컨테이너의 CPU 2개·메모리 4GiB 제한을 적용한다. 최초 SDK·Gradle 다운로드는 캐시가 없으면 오래 걸릴 수 있다.
컨테이너 안에서는 Gradle worker 1개·heap 2GiB·metaspace 512MiB·code cache 128MiB, Kotlin in-process, 테스트 worker 1개·heap 256MiB로 실행한다.
앱의 기본 Gradle 설정을 수정하지 않고 CI 전용 인자·초기화 스크립트로 제한한다. 이 설정은 별도 Kotlin daemon과 테스트 프로세스가 동시에 메모리를 차지해 발생한 첫 격리 빌드의 OOM을 보완한다.
GitLab webhook은 추가하지 않는다. 새 커밋 감지까지 최대 5분 정도 기다릴 수 있다.

## 빌드와 자격정보 분리

1. SCM 체크아웃 후 도구 테스트를 실행한다.
2. Google 공식 SHA-256을 검증한 Android SDK와 Java 21 이미지로 기존 고객 단위 테스트·APK 빌드를 실행한다.
3. `REAL_API`·`POS_KEY`가 빈 debug 설정인지, 테스트 누락·실패·생략이 없는지 확인한다. `local.properties`가 있으면 거절한다.
4. APK 구조·고객 패키지·최소 API 30·서명을 확인하고 체크섬·출처 매니페스트와 함께 Jenkins에 보관한다.
5. 이 단계가 모두 성공한 뒤에만 폴더 범위 Secret file `yamu-apk-s3-credentials`를 바인딩해 업로드한다.

빌드 컨테이너는 CI 에이전트의 이름 있는 작업 볼륨만 공유한다. `--volumes-from`이나 Docker 소켓은 전달하지 않는다.
빌드 컨테이너에 AWS 파일·AWS 환경변수를 전달하지 않는다. 업로드 컨테이너에는 Gradle 캐시·debug 서명키 볼륨을 전달하지 않는다.
Jenkins 에이전트와 작업 볼륨은 신뢰된 팀 코드가 사용하는 공간이다. 이는 악성 코드가 같은 에이전트에서 실행되는 상황에 대한 완전한 격리 경계가 아니다.
Job·SCM·폴더 자격정보 편집 권한을 신뢰된 담당자에게만 준다. 포크·외부 MR 소스를 이 자격정보가 있는 Job에서 실행하지 않는다.

Rootless Docker 볼륨 `yamu-apk-gradle-cache`로 의존성을 캐시하고 `yamu-apk-debug-signing`으로 테스트 서명키를 유지한다.
이 서명키는 정식 출시용이 아니다. 매 빌드마다 서명키를 다시 만들지 않아 같은 CI 앱을 업데이트할 수 있다.
기존 수동 262번 APK와 업데이트 호환이 필요하면 운영자가 최초 실행 전에 해당 개발 서명키를 이 비공개 볼륨에 설치하고 인증서 일치를 확인한다.
서명키·AWS 파일은 S3나 Jenkins 산출물에 넣지 않는다. 작업 종료 시 이번 실행의 컨테이너와 작업 공간만 정리하며 볼륨·다른 서비스를 prune하지 않는다.

## S3와 재실행

버킷은 `yamu-internal-apk-329995585547-ap-northeast-2`이며 Block Public Access 4개가 모두 켜져 있어야 한다.
`publisher-policy.json`은 이 버킷의 비공개 설정 조회와 `builds/android/mock/*`의 Put/Get만 허용한다.
버킷 생성·정책 변경·ACL·삭제·다른 버킷·RDS·Secrets Manager 관리 권한은 업로더에 주지 않는다.
업로더의 AWS 인증 파일은 아래 프로필 형식으로 관리자에 의해 Jenkins Secret file에 등록한다. 값은 채팅·Git·명령 인자로 전달하지 않는다.

```ini
[apk-publisher]
aws_access_key_id=운영자가_비공개로_등록
aws_secret_access_key=운영자가_비공개로_등록
region=ap-northeast-2
```

경로는 `builds/android/mock/{소스SHA}/jenkins-{빌드번호}/{APK해시앞12자리}/`이다.
APK·체크섬·빌드 매니페스트 3개만 올린다. `latest.apk`를 덮어쓰지 않는다.
조건부 Put으로 같은 바이트의 재시도를 허용하고 다른 바이트가 있으면 중단한다. 각 객체의 S3 SHA-256과 크기를 확인한다.
다중 객체 업로드는 하나의 원자적 트랜잭션이 아니다. 중간에 실패한 버전 경로에는 일부 파일이 남을 수 있으나 성공 표시·다운로드 링크는 생성하지 않는다.
실패 시 해당 Jenkins 빌드를 확인하고 같은 파일로 업로드 단계만 재실행할 수 있다. 다른 데이터를 같은 경로로 덮어쓰지 않는다.

다운로드 URL은 서울 리전 SigV4로 24시간만 발급하며 실제 일부 바이트 다운로드와 익명 403을 확인한다.
URL은 다운로드 권한을 담으므로 콘솔·알림에 출력하지 않고 인증된 Jenkins의 `download-url.txt` 산출물로 제공한다.
24시간 뒤 링크를 다시 발급해야 한다. APK 객체는 링크 만료 뒤에도 S3에 남는다.

## 관리자 최초 연결 및 활성화

운영자는 전용 IAM 업로더의 위 정책을 확인하고 키를 비공개로 준비한다. 개인 관리자 AWS 키를 Jenkins에 넣지 않는다.
서버의 Jenkins 홈 안 `.yamu-apk-bootstrap/s3-publisher.ini`에 운영자가 파일을 `0600`으로 준비한다.
저장소에 실제 자격정보나 자격 파일을 만들지 않는다.

1. 작업 브랜치를 push하고 MR을 develop 대상으로 만든다.
2. 인증된 Jenkins 관리자 스크립트 콘솔에서 `install-jenkins.groovy`를 검토·실행한다.
3. 스크립트는 `yamu/mobile` 폴더의 암호화된 Secret file에 등록한 뒤 정확한 bootstrap 파일만 삭제한다. 기존 다른 값은 회전·덮어쓰지 않는다.
4. 검증 Job이 자동 실행된다. 고객 테스트·서명·비공개 S3 업로드·다운로드와 산출물을 확인한다. develop Job은 아직 비활성이다.
5. 사람이 MR을 리뷰·머지한 뒤 `activate-jenkins.groovy`를 실행한다. 이후 develop의 관련 변경을 자동으로 빌드한다.

기존 Jenkins의 폴더·Git 읽기 자격을 재사용하고 기존 CI·CD Job은 수정하지 않는다.
관리자 콘솔 실행 전에는 실제 Jenkins Job 연결을 완료했다고 표시하지 않는다. Job 성공만으로 실제 휴대폰 실행을 완료했다고 표시하지 않는다.
휴대폰에서는 APK 설치 후 디버그 메뉴 → 로그인·연결 끝 → 이 상태로 처음부터로 목데이터 화면을 확인한다.

## 검사

```bash
python3 -m unittest discover -s infra/android -p 'test_*.py' -v
```

이 검사는 실제 AWS 자격이나 Docker 엔진 없이 설정 격리·업로드 계약·조건부 재시도를 확인한다.
실제 SDK·APK·S3 검증 결과는 해당 Jenkins 빌드의 JUnit·build-manifest.json·publication.json으로 구분해 확인한다.

공식 근거: [Android 명령행 빌드](https://developer.android.com/build/building-cmdline),
[SDK 도구·체크섬](https://developer.android.com/studio#command-line-tools-only),
[Jenkins Credentials Binding](https://www.jenkins.io/doc/pipeline/steps/credentials-binding/),
[S3 조건부 쓰기](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).
