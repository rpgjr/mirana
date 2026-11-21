package com.rpgjr.mirana.repository.user;

import com.rpgjr.mirana.enums.OtpPurpose;
import com.rpgjr.mirana.model.entity.user.EmailOtp;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    Optional<EmailOtp> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByIdDesc(Long userId, OtpPurpose purpose);

    Optional<EmailOtp> findFirstByUserIdAndPurposeOrderByIdDesc(Long userId, OtpPurpose purpose);

    long countByUserIdAndPurposeAndCreatedAtAfter(Long userId, OtpPurpose purpose, Instant after);

    @Modifying
    @Query("update EmailOtp o set o.consumedAt = :now where o.userId = :userId and o.purpose = :purpose and o.consumedAt is null")
    int consumeOpen(Long userId, OtpPurpose purpose, Instant now);
}
