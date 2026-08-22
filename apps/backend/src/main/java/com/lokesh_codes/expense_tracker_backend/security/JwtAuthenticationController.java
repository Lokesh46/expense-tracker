package com.lokesh_codes.expense_tracker_backend.security;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.exception.ConflictException;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;
import com.lokesh_codes.expense_tracker_backend.service.CategoryService;

import jakarta.validation.Valid;

@RestController
public class JwtAuthenticationController {

    private final JwtTokenService tokenService;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CategoryService categoryService;

    public JwtAuthenticationController(JwtTokenService tokenService,
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CategoryService categoryService) {
        this.tokenService = tokenService;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.categoryService = categoryService;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<JwtTokenResponse> generateToken(@Valid @RequestBody JwtTokenRequest request) {
        // A failure here raises an AuthenticationException, which the global
        // handler turns into a 401 that does not reveal whether the username exists.
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        return ResponseEntity.ok(new JwtTokenResponse(tokenService.generateToken(authentication)));
    }

    /**
     * Creates an account and seeds it with starter categories, so a new user can
     * record an expense straight away instead of landing on empty screens.
     */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<Map<String, String>> registerUser(@Valid @RequestBody RegisterRequest request) {
        String username = request.username().trim();

        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("That username is already taken.");
        }

        String email = request.email() == null ? null : request.email().trim();
        if (email != null && !email.isBlank() && userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("That email address is already registered.");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        // Role and active status are set by the server, never taken from the request.
        user.setRole("USER");
        user.setActive(true);

        User saved = userRepository.save(user);
        categoryService.seedDefaultsFor(saved);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Account created. You can sign in now."));
    }
}
