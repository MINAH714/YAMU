# FE 개발 및 테스트 안내

`mobile/`은 사용자용 Android 앱, `pos/`는 가맹점용 모의 POS 웹입니다. 현재 두 화면은 YAMU 시작 화면과 BE 연결 상태를 표시합니다. 결제 기능은 아직 없습니다.

## 폴더 구성과 서비스 접점

| 폴더 | 주요 파일 | 역할 |
|---|---|---|
| `mobile/` | `App.tsx`, `app.json`, `.env.example` | Expo·React Native 앱. `EXPO_PUBLIC_API_BASE_URL` 뒤에 `/api/health`를 붙여 BE 호출 |
| `pos/` | `src/App.tsx`, `vite.config.ts`, `.env.example` | Vite·React 웹. 개발 서버의 `/api` 프록시를 거쳐 BE 호출 |

두 앱 모두 [BE](../be/README.md)의 `GET /api/health`가 `{"status":"ok"}`를 반환하면 화면에 `BE 연결 상태: 연결됨`을 표시합니다. [AI](../ai/README.md)는 현재 FE에서 직접 호출하지 않습니다.

## 공통 준비

1. Windows에 [Node.js LTS](https://nodejs.org/en/download)를 설치합니다. 이 저장소의 Expo는 Node.js **22.13 이상(22계열) 또는 24.3 이상(24계열)**을 요구합니다. Node.js 24 LTS를 권장합니다. 설치 후 PowerShell을 새로 열어 버전을 확인합니다.

   ```powershell
   node --version
   npm --version
   ```

   두 명령 모두 버전을 출력해야 합니다. `node` 또는 `npm`을 찾지 못하면 설치 후 PowerShell을 다시 열어 주세요.

2. 저장소의 `S15P31E103` 폴더에서 PowerShell을 엽니다. 아래 명령의 마지막 경로가 `S15P31E103`인지 확인합니다. 이후 명령의 시작 위치는 이 **저장소 루트**입니다.

   ```powershell
   Get-Location
   ```

3. 연결 상태를 확인하려면 [BE 실행 안내](../be/README.md)에 따라 BE를 **8080 포트에서 먼저 실행**합니다. 별도 PowerShell에서 다음 결과가 `ok`인지 확인합니다.

   ```powershell
   (Invoke-RestMethod http://localhost:8080/api/health).status
   ```

## 모바일 앱: 실물 Android폰에서 확인

1. Android폰에 [Expo Go](https://expo.dev/go)를 설치하고 PC와 **같은 Wi-Fi**에 연결합니다. PC에서 `ipconfig`를 실행해 사용 중인 Wi-Fi 어댑터의 `IPv4 주소`를 확인합니다. VPN·가상 어댑터 주소는 사용하지 않습니다.

   ```powershell
   ipconfig
   ```

2. 저장소 루트에서 모바일 폴더로 이동하고 의존성을 설치합니다. `npm ci`는 `package-lock.json`에 기록된 버전으로 설치합니다.

   ```powershell
   Set-Location .\fe\mobile
   npm ci
   Copy-Item .env.example .env
   notepad .env
   ```

3. 메모장에서 `EXPO_PUBLIC_API_BASE_URL`을 PC의 Wi-Fi IPv4 주소로 바꿉니다. 예를 들어 PC 주소가 `192.168.0.10`이면 다음과 같이 저장합니다. 포트 `8080`과 앞의 `http://`를 유지합니다.

   ```dotenv
   EXPO_PUBLIC_API_BASE_URL=http://192.168.0.10:8080
   ```

   `.env.example`의 `10.0.2.2`는 **Android 에뮬레이터 전용**입니다. 에뮬레이터로 검사할 때만 예시 값을 그대로 사용합니다. `.env`는 Git에서 제외되며 `EXPO_PUBLIC_` 값은 앱에 포함되므로 비밀키를 넣지 않습니다.

4. 폰의 브라우저에서 BE 주소를 엽니다. 위 예시 IP라면 `http://192.168.0.10:8080/api/health`이며, 실제 PC 주소로 바꿔 입력합니다. `{"status":"ok"}`가 보이는지 먼저 확인합니다. 이 주소가 열리지 않으면 Expo 화면에서도 BE에 연결되지 않습니다.

5. PC의 모바일 폴더 PowerShell에서 개발 서버를 실행합니다. 표시된 QR 코드를 **Expo Go의 Scan QR code** 메뉴로 스캔합니다.

   ```powershell
   npm start
   ```

   정상 결과는 Android 화면에 `YAMU`, `결제는 간편하게, 혜택은 야무지게.`, `BE 연결 상태: 연결됨`이 표시되는 것입니다. API 확인에는 잠시 `확인 중`이 보일 수 있습니다. `.env`를 고쳤다면 개발 서버를 `Ctrl+C`로 종료한 뒤 다시 실행하고 앱을 다시 엽니다.

6. 코드 검사와 Android 번들 생성은 **새 PowerShell을 저장소 루트에서** 열어 실행합니다. 번들 생성은 APK 설치가 아닌 JavaScript 빌드 검사입니다.

   ```powershell
   Set-Location .\fe\mobile
   npx tsc --noEmit
   npx expo export --platform android
   ```

   첫 명령은 오류 없이 종료되고, 두 번째 명령은 `Android Bundled`와 `Exported: dist`를 출력하면 통과입니다.

## 모의 POS 웹: 브라우저에서 확인

1. **새 PowerShell을 저장소 루트에서** 열고 아래 명령을 실행합니다. `.env.example`의 `/api`는 Vite 개발 서버의 프록시 경로입니다.

   ```powershell
   Set-Location .\fe\pos
   npm ci
   Copy-Item .env.example .env
   Get-Content .env
   npm run lint
   npm run build
   npm run dev
   ```

   `Get-Content .env`는 `VITE_API_BASE_URL=/api`를, 검사·빌드는 오류 없는 종료와 빌드 성공 메시지를 보여야 합니다. 개발 서버는 기본적으로 `http://localhost:5173/`를 출력하고 계속 실행됩니다.

2. 브라우저에서 개발 서버가 출력한 주소를 엽니다. `YAMU 모의 POS`와 `BE 연결 상태: 연결됨`이 보이면 화면 확인이 끝납니다.

3. 다른 PowerShell에서 프록시 요청도 확인합니다. 개발 서버가 5173 이외의 포트를 출력했다면 아래 URL의 포트를 바꿉니다.

   ```powershell
   (Invoke-RestMethod http://localhost:5173/api/health).status
   ```

   결과가 `ok`여야 합니다. POS의 `/api` 요청은 `pos/vite.config.ts`에서 `http://localhost:8080`으로 전달됩니다.

## 연결되지 않을 때

- **두 화면 모두 `연결 안 됨`:** BE 터미널이 실행 중인지 확인하고 `(Invoke-RestMethod http://localhost:8080/api/health).status`를 다시 실행합니다.
- **모바일만 `연결 안 됨`:** `.env`에 `localhost`나 `10.0.2.2` 대신 PC Wi-Fi IPv4를 넣었는지 확인합니다. 폰 브라우저에서도 BE 주소가 열리지 않으면 같은 Wi-Fi, 공유기의 기기 간 통신 설정, Windows 방화벽을 확인합니다.
- **POS만 `연결 안 됨`:** `VITE_API_BASE_URL=/api`인지 확인합니다. BE를 8080이 아닌 포트에서 실행했다면 기본 안내대로 8080에서 다시 실행하거나 `pos/vite.config.ts`의 프록시 주소를 함께 수정해야 합니다.
- **`API 주소를 설정해 주세요`:** 해당 앱의 `.env`가 있는지, 변수 이름이 예시와 같은지 확인하고 개발 서버를 재시작합니다.

테스트가 끝나면 실행 중인 Expo·Vite 터미널에서 `Ctrl+C`로 서버를 종료합니다.
