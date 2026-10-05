package com.lavarapido.operations.infrastructure.adapter.in.web;

import com.lavarapido.operations.domain.exception.InvalidValueException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Quien llama, leido de un token ya verificado. El claim sub es el user_id de app_user. */
final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    static long userId(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidValueException("INVALID_TOKEN", "The token has no subject claim");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new InvalidValueException("INVALID_TOKEN", "The token subject is not a valid user id");
        }
    }
}
