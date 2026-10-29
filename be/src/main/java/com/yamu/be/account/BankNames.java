package com.yamu.be.account;

import java.util.Map;

/**
 * 금융망 은행 코드 → 화면용 은행 이름. 계좌 연결 때는 SSAFY 예금주 조회가 이름을 주지만 저장하지 않아서,
 * 목록·수정 응답은 이 표로 채운다. 표에 없는 코드는 코드를 그대로 이름으로 쓴다.
 */
final class BankNames {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("001", "한국은행"),
            Map.entry("002", "산업은행"),
            Map.entry("003", "기업은행"),
            Map.entry("004", "국민은행"),
            Map.entry("011", "농협은행"),
            Map.entry("020", "우리은행"),
            Map.entry("023", "SC제일은행"),
            Map.entry("027", "시티은행"),
            Map.entry("032", "대구은행"),
            Map.entry("034", "광주은행"),
            Map.entry("035", "제주은행"),
            Map.entry("037", "전북은행"),
            Map.entry("039", "경남은행"),
            Map.entry("045", "새마을금고"),
            Map.entry("081", "하나은행"),
            Map.entry("088", "신한은행"),
            Map.entry("090", "카카오뱅크"),
            Map.entry("999", "싸피은행"));

    private BankNames() {
    }

    static String of(String code) {
        return NAMES.getOrDefault(code, code);
    }
}
