# BE 개발 및 테스트 안내

`be/`는 모바일 앱과 모의 POS가 호출하는 Spring Boot API입니다. 현재 구현된 API는 `GET /api/health` 하나이며 응답은 `{"status":"ok"}`입니다. DB·Redis·인증·결제·AI 호출은 아직 연결되지 않았습니다.

## 폴더 구성

| 경로 | 역할 |
|---|---|
| `src/main/java/com/yamu/be/` | 애플리케이션 시작점과 상태 확인 컨트롤러 |
| `src/main/resources/application.yml` | 앱 이름과 기본 포트 8080 설정 |
| `src/test/java/com/yamu/be/` | `GET /api/health` 자동 테스트 |
| `build.gradle`, `settings.gradle` | Spring Boot 의존성과 Java 17 컴파일 설정 |
| `gradlew.bat`, `gradle/wrapper/` | Gradle을 따로 설치하지 않고 실행하는 Wrapper |
| `.env.example` | 선택적 `SERVER_PORT` 값의 예시 |

## 1. 설치와 버전 확인

1. Windows에 **JDK 17 이상**을 설치합니다. [Eclipse Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17&os=windows&package=jdk)을 사용할 수 있습니다. JRE만 설치하면 컴파일할 수 없습니다. 로컬 Java 21로 실행해도 컴파일 결과는 Java 17 대상입니다.
2. 설치 후 PowerShell을 새로 열고 다음을 실행합니다. `java`와 `javac` 모두 17 이상 버전을 출력해야 합니다.

   ```powershell
   java -version
   javac -version
   ```

3. 저장소의 `S15P31E103` 폴더에서 PowerShell을 연 뒤 BE 폴더로 이동합니다. 첫 실행에는 Gradle Wrapper와 Maven 의존성을 내려받으므로 인터넷 연결이 필요합니다.

   ```powershell
   Set-Location .\be
   ```

## 2. 자동 테스트 실행

BE 폴더에서 다음을 실행합니다.

```powershell
.\gradlew.bat test
```

`BUILD SUCCESSFUL`이 표시되면 통과입니다. 이 테스트는 `GET /api/health`의 HTTP 200과 `status=ok`를 확인합니다. BE 폴더에서 `Start-Process .\build\reports\tests\test\index.html`을 실행하면 자세한 결과를 브라우저에서 볼 수 있습니다.

## 3. 서버 실행과 HTTP 응답 확인

1. BE 폴더에서 서버를 실행합니다. 터미널은 서버가 실행되는 동안 열린 상태로 둡니다.

   ```powershell
   .\gradlew.bat bootRun
   ```

   로그에 `Tomcat started on port 8080`과 `Started YamuBeApplication`이 나오면 기동된 것입니다.

2. **새 PowerShell**을 열어 다음 명령을 실행합니다. 어느 폴더에서 실행해도 됩니다.

   ```powershell
   (Invoke-RestMethod http://localhost:8080/api/health).status
   ```

   화면에 `ok`가 출력되면 API가 실제로 응답한 것입니다. 브라우저에서 `http://localhost:8080/api/health`를 열면 `{"status":"ok"}`를 볼 수도 있습니다.

## 환경변수와 다른 서비스

| 변수 | 기본값 | 용도 |
|---|---|---|
| `SERVER_PORT` | `8080` | BE 서버 포트 |

`application.yml`이 `SERVER_PORT`를 읽습니다. `.env.example`은 값의 **예시**이며 Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다. 포트를 바꾸려면 `bootRun`을 실행할 **같은 PowerShell**에서 아래처럼 설정합니다.

```powershell
$env:SERVER_PORT = '8081'
.\gradlew.bat bootRun
```

이 경우 응답 확인 URL도 `http://localhost:8081/api/health`로 바꿔야 합니다. [모바일](../fe/README.md)은 설정한 BE 주소를 사용하고, POS 개발 프록시는 기본값인 **8080 포트**를 바라보므로 처음에는 기본 포트로 테스트하세요. [AI 서비스](../ai/README.md)는 독립적으로 실행되며 현재 BE와 통신하지 않습니다.

## 문제 해결과 종료

- `java` 또는 `javac`를 찾지 못하면 JDK 설치와 `JAVA_HOME`/`PATH`를 확인하고 PowerShell을 새로 엽니다.
- 첫 실행의 Gradle 다운로드가 실패하면 인터넷 연결을 확인한 후 같은 명령을 다시 실행합니다.
- `Port 8080 was already in use`가 나오면 해당 포트의 다른 서버를 종료하거나 위의 `SERVER_PORT` 설정을 사용합니다.
- `Invoke-RestMethod`가 연결 실패를 반환하면 `bootRun` 터미널이 계속 실행 중인지, 시작 로그에 오류가 없는지 확인합니다.

테스트가 끝나면 `bootRun` 터미널에서 `Ctrl+C`로 서버를 종료합니다.
