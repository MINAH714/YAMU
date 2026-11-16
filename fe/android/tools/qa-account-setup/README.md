# 실제 설치의 기기 준비 도구

이 소스는 init script를 명시한 임시 APK에만 들어갑니다. 정상 빌드·CI·최종 APK에는 Activity와 도구 코드가 포함되지 않습니다. 서버 호출, PIN 수집, 토큰 발급, 계정 초기화 기능은 없습니다. 공개키 증명 저장과 서버 준비 결과의 기기 번호 가져오기만 수행합니다.

임시 Activity는 `android.permission.DUMP`를 가진 ADB shell 등 운영 도구에서만 실행할 수 있습니다. 일반 앱의 외부 Intent 호출은 허용하지 않습니다. 휴대폰에서 USB 디버깅을 승인한 운영자가 아래 명령으로 실행합니다.

## 빌드와 설치

실제 시연에 사용할 `customerRelease`와 **동일 applicationId(`com.yamu.app`)·동일 서명 인증서**를 사용합니다. 별도 debug 서명이나 `.qa` 앱 ID에서 만든 공개키를 최종 release에 등록하지 않습니다. 기존 릴리스 서명 설정은 보호된 `local.properties`로 공급하며 키/비밀번호를 출력하지 않습니다.

```powershell
# fe/android에서, 승인된 기존 시연 서명 설정 사용
.\gradlew.bat -I tools/qa-account-setup/setup.init.gradle assembleCustomerRelease -PqaSetupVariant=customerRelease -PqaSetupReleaseAcknowledged=true '-Pyamu.releaseBaseUrl=https://yamu.it.kr/api/'
```

준비용과 최종 APK의 패키지·인증서·versionCode를 비교한 뒤 `adb -s <실제기기> install -r <준비용.apk>`로 업데이트합니다. **앱 삭제, 데이터 삭제, Keystore 키 재생성, 다른 서명으로 재설치를 하지 않습니다.** 기존 로그인/등록 기기가 있는 설치는 도구가 거절합니다. 그 경우 기존 데이터를 지우지 말고 미사용 휴대폰/독립 Android 프로필을 준비합니다. 실제 기기의 USB 디버깅 허용은 소유자가 수행합니다.

## 공개 증명 저장

각 실제 설치에서 운영 DB로 확인한 dataset/user 번호로 실행합니다. 아래 번호는 현 운영 적재 기준의 예시이며 실행 전 확인이 필요합니다.

```powershell
adb -s <최인국-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-1 --el userId 11
adb -s <남윤주-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-2 --el userId 12
adb -s <박준수-기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action export --es datasetKey qa-550-life-3 --el userId 13
```

화면의 **등록 자료 저장**을 눌러 파일 선택기에서 JSON을 저장하고 운영 담당자에게 전달합니다. release는 `run-as`가 불가능하므로 파일 선택기를 사용합니다. JSON은 공개 자료이며 PIN·개인키·토큰이 없습니다. 서명 유효기간(24시간) 전에 BE의 `plan/prepare`를 진행합니다. 대상이 틀리면 서버에 등록하기 전에 올바른 대상의 증명을 다시 만듭니다. 세 설치의 키·UUID는 서로 달라야 합니다.

## receipt 가져오기와 정상 로그인

운영 도구의 `prepared=true` 결과를 받은 뒤, 해당 휴대폰에서 비밀값 없는 receipt JSON을 Base64url로 전달합니다. JSON을 직접 명령행에 넣으면 PowerShell→ADB→Android shell 사이에서 따옴표가 손실될 수 있으므로 아래처럼 인코딩합니다. PIN이나 토큰을 명령행에 넣지 않습니다. Base64는 암호화가 아니며 이 파일에는 공개 기기 매핑만 있어야 합니다.

```powershell
$receipt = Get-Content -LiteralPath '<검증한 receipt 경로>' -Raw
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($receipt)).TrimEnd('=').Replace('+','-').Replace('/','_')
adb -s <실제기기> shell am start -n com.yamu.app/com.yamu.app.QaAccountSetupActivity --es action import --es receiptBase64 $encoded
```

도구는 저장했던 대상 사용자·설치 UUID·현재 Keystore 공개키 지문과 receipt를 비교합니다. 일치할 때만 `userDeviceId`를 저장하며 기존 사용자·토큰을 덮어쓰지 않습니다. 정상 로그인 화면에서 사용자 본인이 정한 PIN으로 로그인합니다. 도구가 인증을 대신하거나 세션을 생성하지 않습니다.

마지막으로 **init script 없이** 동일 앱 ID·서명으로 최종 APK를 빌드하고 `adb install -r`로 업데이트합니다. 기기 키·기기 번호가 보존되는지 확인하고 다시 정상 로그인합니다. 최종 APK에 `QaAccountSetupActivity`가 없는지도 검사합니다. 임시 APK를 배포 링크에 최종본으로 올리지 않습니다.

현재 앱은 설치당 한 사용자 기기를 저장합니다. 세 사용자 전환은 각각의 휴대폰/Android 프로필에서 해당 앱을 여는 방식입니다. 로그아웃해도 다른 사람으로 바뀌지 않습니다.

## 격리 검증

`customerDebug`는 별도 앱 ID로 테스트할 수 있습니다. 이 키는 운영 등록에 사용하지 않습니다.

```powershell
.\gradlew.bat -I tools/qa-account-setup/setup.init.gradle testCustomerDebugUnitTest assembleCustomerDebug '-Pyamu.appIdSuffix=.lifecheck'
```

[BE 실행·안전 조건](../../../../be/seed/qa/ACCOUNT-PREPARATION.md)을 함께 따릅니다.
