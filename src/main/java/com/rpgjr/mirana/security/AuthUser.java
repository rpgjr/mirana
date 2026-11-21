package com.rpgjr.mirana.security;

import java.io.Serializable;
import java.security.Principal;

/** Session principal. getName() is the user id so sessions can be found and revoked per user. */
public record AuthUser(Long id, String email, String displayName) implements Principal, Serializable {

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
