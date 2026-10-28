package com.yamu.be.user.domain;

/** app_user.status. CHECK 제약과 같은 값이다. */
public enum AppUserStatus {
    /** 가입 진행 중이거나 시드로 미리 넣은 페르소나. 로그인할 수 없다. */
    PENDING,
    ACTIVE,
    WITHDRAWN
}
