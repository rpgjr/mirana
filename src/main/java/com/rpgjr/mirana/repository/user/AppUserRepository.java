package com.rpgjr.mirana.repository.user;

import com.rpgjr.mirana.model.entity.user.AppUser;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);
}
