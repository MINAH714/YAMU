package com.yamu.be.user.domain;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 같은 사람 찾기. UK(phone_hash, birth_date)라 많아야 한 명이다. phoneHash는 LookupHasher.phone()으로 만든다. */
    Optional<AppUser> findByPhoneHashAndBirthDate(byte[] phoneHash, LocalDate birthDate);
}
