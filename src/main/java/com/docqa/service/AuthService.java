package com.docqa.service;

import com.docqa.dto.request.AuthRequest;
import com.docqa.dto.request.RegisterRequest;
import com.docqa.dto.response.AuthResponse;
import com.docqa.exception.ApiException;
import com.docqa.exception.UserAlreadyExistsException;
import com.docqa.model.User;
import com.docqa.repository.UserRepository;
import com.docqa.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .build();

        user = userRepository.save(user);

        String token = jwtTokenProvider.generateToken(toUserDetails(user));
        return buildResponse(token, user);
    }

    public AuthResponse login(AuthRequest request) {
        // Throws BadCredentialsException → handled by GlobalExceptionHandler → 401
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmailAndIsActiveTrue(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found or disabled"));

        String token = jwtTokenProvider.generateToken(toUserDetails(user));
        return buildResponse(token, user);
    }

    private UserDetails toUserDetails(User user) {
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    private AuthResponse buildResponse(String token, User user) {
        return new AuthResponse(
                token,
                expirationMs,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        );
    }
}
