package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-account-lookup-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountQueryServiceTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountQueryService service;

    OwnershipFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
    }

    @Test
    void 본인의_연결된_계좌는_돌려준다() {
        long owner = fixtures.user();
        long account = fixtures.account(owner);

        LinkedAccountRef ref = service.requireConnectedAccount(owner, account);

        assertThat(ref).isEqualTo(new LinkedAccountRef(account, "088", "5934"));
    }

    @Test
    void 남의_계좌는_있어도_없는_것처럼_404다() {
        long owner = fixtures.user();
        long strangerAccount = fixtures.account(fixtures.user());

        assertNotFound(() -> service.requireConnectedAccount(owner, strangerAccount));
    }

    @Test
    void 연결을_해제한_계좌는_404다() {
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED' WHERE linked_account_id = ?", account);

        assertNotFound(() -> service.requireConnectedAccount(owner, account));
    }

    @Test
    void 없는_번호나_빈_번호는_404다() {
        long owner = fixtures.user();

        assertNotFound(() -> service.requireConnectedAccount(owner, Long.MAX_VALUE));
        assertNotFound(() -> service.requireConnectedAccount(owner, null));
        assertNotFound(() -> service.requireConnectedAccount(null, fixtures.account(owner)));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
