package com.yamu.be.user.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 같은 사람 찾기. UK(phone_hash, birth_date)라 많아야 한 명이다. phoneHash는 LookupHasher.phone()으로 만든다. */
    Optional<AppUser> findByPhoneHashAndBirthDate(byte[] phoneHash, LocalDate birthDate);

    /**
     * 사용자 행을 잠근다(SELECT … FOR UPDATE). 기기 등록이 동시에 두 번 와도 기기 수 확인·ACTIVE 전환을 한 줄로 세운다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);
}
