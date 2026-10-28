package com.yamu.be.auth.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceEventRepository extends JpaRepository<UserDeviceEvent, Long> {

    List<UserDeviceEvent> findByUserDeviceIdOrderByIdAsc(Long userDeviceId);
}
