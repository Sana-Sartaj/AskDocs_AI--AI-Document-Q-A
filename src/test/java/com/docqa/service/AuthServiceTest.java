package com.docqa.service;

import com.docqa.dto.request.AuthRequest;
import com.docqa.dto.request.RegisterRequest;
import com.docqa.dto.response.AuthResponse;
import com.docqa.exception.ApiException;
import com.docqa.exception.UserAlreadyExistsException;
import com.docqa.model.User;
import com.docqa.repository.UserRepository;
import com.docqa.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AuthenticationManager authenticationManager;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider, authenticationManager);
        ReflectionTestUtils.setField(authService, "expirationMs", 3600000L);
    }

    @Test
    void register_newUser_returnsAuthResponse() {
        given(userRepository.existsByEmail("alice@example.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("$hashed$");
        given(jwtTokenProvider.generateToken(any(UserDetails.class))).willReturn("jwt-token");
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        AuthResponse response = authService.register(
                new RegisterRequest("alice@example.com", "password123", "Alice"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.expiresIn()).isEqualTo(3600000L);
    }

    @Test
    void register_savesUserWithEncodedPassword() {
        given(userRepository.existsByEmail(anyString())).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("$hashed$");
        given(jwtTokenProvider.generateToken(any())).willReturn("tok");
        given(userRepository.save(any(User.class))).willAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(new RegisterRequest("alice@example.com", "password123", "Alice"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$hashed$");
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void register_duplicateEmail_throwsUserAlreadyExistsException() {
        given(userRepository.existsByEmail("alice@example.com")).willReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice@example.com", "password123", "Alice")))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("alice@example.com");
    }

    @Test
    void login_validCredentials_returnsAuthResponse() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .email("alice@example.com")
                .passwordHash("$hashed$")
                .fullName("Alice")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        given(authenticationManager.authenticate(any())).willReturn(
                new UsernamePasswordAuthenticationToken("alice@example.com", "password123"));
        given(userRepository.findByEmailAndIsActiveTrue("alice@example.com"))
                .willReturn(Optional.of(user));
        given(jwtTokenProvider.generateToken(any(UserDetails.class))).willReturn("jwt-token");

        AuthResponse response = authService.login(
                new AuthRequest("alice@example.com", "password123"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void login_badCredentials_propagatesBadCredentialsException() {
        given(authenticationManager.authenticate(any()))
                .willThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(
                new AuthRequest("alice@example.com", "wrong-pass")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_userNotFoundAfterAuth_throwsApiException() {
        given(authenticationManager.authenticate(any())).willReturn(
                new UsernamePasswordAuthenticationToken("ghost@example.com", "pass12345"));
        given(userRepository.findByEmailAndIsActiveTrue("ghost@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new AuthRequest("ghost@example.com", "pass12345")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Account not found or disabled");
    }
}
