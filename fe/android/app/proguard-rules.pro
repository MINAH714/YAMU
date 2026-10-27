# release 빌드 축소(R8) 규칙 — 앱이 따로 정할 것만(이유 · 확인 방법은 17 문서).
# 라이브러리가 들고 오는 규칙(kotlinx.serialization · Retrofit · OkHttp · Hilt · Navigation · DataStore · Biometric ·
# Play services · CameraX · ML Kit)은 다시 쓰지 않는다. ZXing · Keystore(JCA)는 리플렉션이 없어 규칙이 필요 없다.

# 응답 DTO 지키기 — 응답 data를 읽지 않는 API(예: auth/signup)는 R8이 DTO를 지우고 반환형 서명을 ApiEnvelope<Object>로 바꾼다.
# 그러면 부를 때 Retrofit이 serializer를 못 찾아 죽는다(IllegalArgumentException). 그래서 Retrofit 인터페이스가 남은 패키지의
# @Serializable 클래스는 지우지 않는다. 이름 바꾸기는 되고, Companion · serializer()는 kotlinx.serialization 규칙이 지킨다
-if interface com.yamu.app.**.* { @retrofit2.http.* <methods>; }
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.yamu.app.<1>.*

# 로그 정리 — Log.v · d · i 호출을 지운다(라이브러리 것도). 앱은 debug에서만 찍지만 실수로 남겨도 release에선 빠지게
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
