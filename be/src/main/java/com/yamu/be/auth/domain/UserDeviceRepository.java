package com.yamu.be.auth.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
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

    /** UK(user_id, device_uuid). 해제된 기기도 찾는다(같은 폰을 다시 등록하면 그 행을 다시 쓴다). */
    @Query("select d from UserDevice d where d.user.id = :userId and d.deviceUuid = :deviceUuid")
    Optional<UserDevice> findByUserIdAndDeviceUuid(@Param("userId") Long userId, @Param("deviceUuid") String deviceUuid);

    /** 해제되지 않은(ACTIVE·LOCKED) 기기 수. 사용자당 3대까지(명세 「기기 등록」). */
    @Query("select count(d) from UserDevice d where d.user.id = :userId and d.status <> :status")
    long countByUserIdAndStatusNot(@Param("userId") Long userId, @Param("status") DeviceStatus status);

    /** 로그인을 끊을 기기 번호들(PIN 변경 뒤 다른 기기의 Refresh Token 폐기). */
    @Query("select d.id from UserDevice d where d.user.id = :userId and d.status <> :status")
    List<Long> findIdsByUserIdAndStatusNot(@Param("userId") Long userId, @Param("status") DeviceStatus status);

    /** 해제되지 않은 기기들. 기기 재등록이 성공하면 이 폰 말고는 모두 해제한다. */
    @Query("select d from UserDevice d where d.user.id = :userId and d.status <> :status")
    List<UserDevice> findByUserIdAndStatusNot(@Param("userId") Long userId, @Param("status") DeviceStatus status);

    /** 기기 목록(명세 「등록 기기 목록 조회」). 해제한 기기도 이력으로 보여 준다. */
    @Query("select d from UserDevice d where d.user.id = :userId")
    List<UserDevice> findAllByUserId(@Param("userId") Long userId);
}
