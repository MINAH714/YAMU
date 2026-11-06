package com.yamu.be.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserQueryServiceTest {

    @Test
    void 휴대폰_번호는_가운데를_가린다() {
        assertThat(UserQueryService.maskPhone("01012345678")).isEqualTo("010-****-5678");
        assertThat(UserQueryService.maskPhone("0111234567")).isEqualTo("011-***-4567");
    }

    @Test
    void 형식이_다른_번호는_끝_4자리만_남기고_없으면_null이다() {
        assertThat(UserQueryService.maskPhone("123456789")).isEqualTo("*****6789");
        assertThat(UserQueryService.maskPhone("12")).isEqualTo("**");
        assertThat(UserQueryService.maskPhone(null)).isNull();
    }
}
