package com.rpgjr.mirana.service.auth;

import com.rpgjr.mirana.security.AuthUser;

import com.rpgjr.mirana.model.entity.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public void start(AppUser user, HttpServletRequest req, HttpServletResponse res) {
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getDisplayName());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
        repo.saveContext(ctx, req, res);
    }

    public void end(HttpServletRequest req) {
        var session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** Ends every session of the user except the one with the given id. */
    public void revokeOthers(Long userId, String keepSessionId) {
        sessions.findByPrincipalName(String.valueOf(userId)).keySet().stream()
                .filter(id -> !id.equals(keepSessionId)).forEach(sessions::deleteById);
    }

    public void revokeAll(Long userId) {
        sessions.findByPrincipalName(String.valueOf(userId)).keySet().forEach(sessions::deleteById);
    }
}
