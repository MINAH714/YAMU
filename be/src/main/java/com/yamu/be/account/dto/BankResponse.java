package com.yamu.be.account.dto;

/** @param code 금융망 은행 코드(예: "088") */
public record BankResponse(String code, String name) {
}
