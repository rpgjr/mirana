package com.rpgjr.mirana.repository.user;

import com.rpgjr.mirana.model.entity.user.TrustedDevice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {
    Optional<TrustedDevice> findByUserIdAndTokenHash(Long userId, String tokenHash);

    List<TrustedDevice> findByUserIdOrderByLastSeenAtDesc(Long userId);

    Optional<TrustedDevice> findByIdAndUserId(Long id, Long userId);
}
