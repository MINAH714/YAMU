# 실제 설치의 기기 준비 도구

이 소스는 init script를 명시한 임시 APK에만 들어갑니다. 정상 빌드·CI·최종 APK에는 Activity와 도구 코드가 포함되지 않습니다. 서버 호출, PIN 수집, 토큰 발급, 계정 초기화 기능은 없습니다. 공개키 증명 저장과 서버 준비 결과의 기기 번호 가져오기만 수행합니다.

임시 Activity는 `android.permission.DUMP`를 가진 ADB shell 등 운영 도구에서만 실행할 수 있습니다. 일반 앱의 외부 Intent 호출은 허용하지 않습니다. 휴대폰에서 USB 디버깅을 승인한 운영자가 아래 명령으로 실행합니다.

## 빌드와 설치

준비 도구는 **명시적인 customerDebug 전용**입니다. release variant를 지정하거나 release 태스크에 이 init script를 함께 쓰면 빌드를 거절합니다. 일반 customerDebug/customerRelease는 이 init script를 생략하므로 도구를 포함하지 않습니다.

독립 에뮬레이터 또는 독립 Android 설치 3개를 사용합니다. 실제 휴대폰 3대는 필수 조건이 아닙니다. 기존 로컬 preview 설치의 UUID·PIN·개인키·토큰은 복사하지 않습니다. 각 새 설치에서 UUID와 Android Keystore 키를 만들고 그 설치의 공개 증명만 서버로 전달합니다.

```powershell
# fe/android. 보호된 local.properties의 yamu.baseUrl=https://yamu.it.kr/api/ 확인
.\gradlew.bat -I tools/qa-account-setup/setup.init.gradle testCustomerDebugUnitTest assembleCustomerDebug
```

준비 APK와 도구 없는 최종 검증 APK는 같은 applicationId·서명으로 빌드하고 `adb install -r`로 업데이트합니다. **앱 삭제·데이터 삭제·기기 개인키 복사·재생성을 하지 않습니다.** 기존 정상 기기 정보가 있는 설치는 먼저 재사용 가능 여부를 확인합니다. 임시 도구로 다른 사용자의 기기나 로그인 세션을 덮어쓰지 않습니다. 추후 다른 서명의 customerRelease 또는 실제 휴대폰으로 바꾸려면 별도 기기 등록/복구 계획이 필요합니다.

## 공개 증명 저장

각 독립 설치에서 운영 DB로 확인한 dataset/user 번호로 실행합니다. 아래 번호는 현 운영 적재 기준의 예시이며 실행 전 확인이 필요합니다.

```powershell
adb -s <최인국-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-1 --el userId 11
adb -s <남윤주-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-2 --el userId 12
adb -s <박준수-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-3 --el userId 13
```

화면의 **등록 자료 저장**을 눌러 파일 선택기에서 JSON을 저장하고 운영 담당자에게 전달합니다. customerDebug는 `adb exec-out run-as com.yamu.app cat files/qa-account-device.json`으로 공개 증명만 추출할 수도 있습니다. JSON은 공개 자료이며 PIN·개인키·토큰이 없습니다. 서명 유효기간(24시간) 전에 BE의 `plan/prepare`를 진행합니다. 대상이 틀리면 서버에 등록하기 전에 올바른 대상의 증명을 다시 만듭니다. 세 설치의 키·UUID는 서로 달라야 합니다.

## receipt 가져오기와 정상 로그인

운영 도구의 `prepared=true` 결과를 받은 뒤, 해당 설치에서 비밀값 없는 receipt JSON을 Base64url로 전달합니다. JSON을 직접 명령행에 넣으면 PowerShell→ADB→Android shell 사이에서 따옴표가 손실될 수 있으므로 아래처럼 인코딩합니다. PIN이나 토큰을 명령행에 넣지 않습니다. Base64는 암호화가 아니며 이 파일에는 공개 기기 매핑만 있어야 합니다.

```powershell
$receipt = Get-Content -LiteralPath '<검증한 receipt 경로>' -Raw
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($receipt)).TrimEnd('=').Replace('+','-').Replace('/','_')
adb -s <실제기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action import --es receiptBase64 $encoded
```

도구는 저장했던 대상 사용자·설치 UUID·현재 Keystore 공개키 지문과 receipt를 비교합니다. 일치할 때만 `userDeviceId`를 저장하며 기존 사용자·토큰을 덮어쓰지 않습니다. 정상 로그인 화면에서 사용자 본인이 정한 PIN으로 로그인합니다. 도구가 인증을 대신하거나 세션을 생성하지 않습니다.

마지막으로 **init script 없이** 동일 앱 ID·서명으로 최종 APK를 빌드하고 `adb install -r`로 업데이트합니다. 기기 키·기기 번호가 보존되는지 확인하고 다시 정상 로그인합니다. 최종 APK에 `QaAccountSetupActivity`가 없는지도 검사합니다. 임시 APK를 배포 링크에 최종본으로 올리지 않습니다.

현재 앱은 설치당 한 사용자 기기를 저장합니다. 세 사용자 전환은 각각의 에뮬레이터/휴대폰/Android 프로필에서 해당 앱을 여는 방식입니다. 로그아웃해도 다른 사람으로 바뀌지 않습니다.

## 격리 검증

`customerDebug`는 별도 앱 ID로 테스트할 수 있습니다. 운영 QA 등록에는 사용할 실제 독립 설치에서 생성한 새 키만 사용합니다. 기존 로컬 fixture의 키는 재사용하지 않습니다.

```powershell
.\gradlew.bat -I tools/qa-account-setup/setup.init.gradle testCustomerDebugUnitTest assembleCustomerDebug '-Pyamu.appIdSuffix=.lifecheck'
```

[BE 실행·안전 조건](../../../../be/seed/qa/ACCOUNT-PREPARATION.md)을 함께 따릅니다.
