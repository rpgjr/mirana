package com.rpgjr.mirana.repository.user;

import com.rpgjr.mirana.model.entity.user.UserCredential;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {
}
