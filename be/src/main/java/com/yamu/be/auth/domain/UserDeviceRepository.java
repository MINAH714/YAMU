package com.yamu.be.auth.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    /**
     * 로그인 검증 동안 기기 행을 잠근다(SELECT … FOR UPDATE).
     * 틀린 PIN이 동시에 여러 번 와도 실패 횟수를 하나씩 줄 세워 세므로 5회째에 정확히 잠긴다.
     * user를 join fetch하지 않는다. 같이 가져오면 MySQL이 app_user 행까지 잠가 같은 사용자의 다른 작업이 BCrypt 동안 기다린다.
     * user는 같은 트랜잭션 안에서 잠금 없이 따로 읽힌다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from UserDevice d where d.id = :id")
    Optional<UserDevice> findByIdForUpdate(@Param("id") Long id);

    @Query("select d from UserDevice d join fetch d.user where d.id = :id")
    Optional<UserDevice> findWithUserById(@Param("id") Long id);
}
