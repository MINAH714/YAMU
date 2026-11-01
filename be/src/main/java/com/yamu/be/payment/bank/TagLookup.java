package com.yamu.be.payment.bank;

import java.util.List;

/**
 * 거래내역에서 태그로 거래를 찾은 결과({@link SsafyTransferApi#findByTag}).
 *
 * @param result  찾은 결과 네 가지
 * @param matches 태그와 방향이 같은 거래 전부. 금액이 다른 거래도 들어 있다
 */
public record TagLookup(Result result, List<BankTransaction> matches) {

    public TagLookup {
        matches = List.copyOf(matches);
    }

    public enum Result {

        /** 태그·방향·금액이 같은 거래가 딱 한 건 있다. 그 요청은 처리됐다. */
        FOUND,

        /** 같은 태그의 거래가 없다. 조회 기간 안에서는 처리되지 않았다. */
        NOT_FOUND,

        /** 태그는 한 건 있는데 금액이 다르다. 자동으로 판단하지 않고 운영자가 본다. */
        AMOUNT_MISMATCH,

        /** 같은 태그의 거래가 두 건 이상이다. 두 번 처리된 것이므로 자동으로 판단하지 않고 운영자가 본다. */
        DUPLICATED
    }

    /** 찾은 거래 한 건. {@link Result#FOUND}일 때만 부른다. */
    public BankTransaction found() {
        if (result != Result.FOUND) {
            throw new IllegalStateException("찾은 거래가 한 건이 아닙니다: " + result);
        }
        return matches.get(0);
    }
}
