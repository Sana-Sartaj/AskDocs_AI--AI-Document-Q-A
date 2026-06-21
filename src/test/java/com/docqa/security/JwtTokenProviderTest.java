package com.docqa.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    // base64("secretkeyfortestingpurposesdonotuse") = 34 bytes = 272 bits, valid for HMAC-SHA256
    private static final String VALID_SECRET = "c2VjcmV0a2V5Zm9ydGVzdGluZ3B1cnBvc2VzZG9ub3R1c2U=";
    private static final long EXPIRATION_MS = 3_600_000L; // 1 hour

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secret", VALID_SECRET);
        ReflectionTestUtils.setField(provider, "expirationMs", EXPIRATION_MS);
    }

    private UserDetails userDetails(String email) {
        return new User(email, "encoded", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void generateToken_returnsNonBlankToken() {
        String token = provider.generateToken(userDetails("alice@example.com"));
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    void extractUsername_returnsSubjectFromToken() {
        String token = provider.generateToken(userDetails("alice@example.com"));
        assertThat(provider.extractUsername(token)).isEqualTo("alice@example.com");
    }

    @Test
    void isTokenValid_freshToken_returnsTrue() {
        UserDetails ud = userDetails("alice@example.com");
        String token = provider.generateToken(ud);
        assertThat(provider.isTokenValid(token, ud)).isTrue();
    }

    @Test
    void isTokenValid_wrongUser_returnsFalse() {
        String token = provider.generateToken(userDetails("alice@example.com"));
        assertThat(provider.isTokenValid(token, userDetails("bob@example.com"))).isFalse();
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = provider.generateToken(userDetails("alice@example.com"));
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_garbageToken_returnsFalse() {
        assertThat(provider.validateToken("not.a.valid.token")).isFalse();
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(provider, "expirationMs", -1000L);
        String expiredToken = provider.generateToken(userDetails("alice@example.com"));
        assertThat(provider.validateToken(expiredToken)).isFalse();
    }
}
