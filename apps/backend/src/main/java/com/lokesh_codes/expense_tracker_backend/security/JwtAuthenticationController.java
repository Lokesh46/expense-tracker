package com.lokesh_codes.expense_tracker_backend.security;

import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.regex.Pattern;

@RestController
public class JwtAuthenticationController {

    private final JwtTokenService tokenService;

    private final AuthenticationManager authenticationManager;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public JwtAuthenticationController(JwtTokenService tokenService,
            AuthenticationManager authenticationManager, UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.tokenService = tokenService;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<JwtTokenResponse> generateToken(
            @RequestBody JwtTokenRequest jwtTokenRequest) {

        var authenticationToken = new UsernamePasswordAuthenticationToken(
                jwtTokenRequest.username(),
                jwtTokenRequest.password());

        var authentication = authenticationManager.authenticate(authenticationToken);

        var token = tokenService.generateToken(authentication);

        return ResponseEntity.ok(new JwtTokenResponse(token));
    }

    @PostMapping("/register")
    public ResponseEntity<String> registerUser(@RequestBody RegisterRequest registerRequest) {
        Optional<User> existingUser = userRepository.findByUsername(registerRequest.username());

        if (existingUser.isPresent()) {
            return ResponseEntity.status(409).body("Username already exists.");
        }

        // If email provided, validate format and uniqueness
        if (registerRequest.email() != null && !registerRequest.email().isBlank()) {
            String email = registerRequest.email().trim();
            if (!isValidEmail(email)) {
                return ResponseEntity.badRequest().body("Invalid email format.");
            }
            if (userRepository.findByEmail(email).isPresent()) {
                return ResponseEntity.status(409).body("Email already in use.");
            }
        }


        // Encode the password before saving
        User newUser = new User();
        newUser.setUsername(registerRequest.username());
        newUser.setPassword(passwordEncoder.encode(registerRequest.password()));

        // Set optional fields if provided
        if (registerRequest.email() != null) {
            newUser.setEmail(registerRequest.email());
        }

        // role: default to 'USER' if not provided
        if (registerRequest.role() != null) {
            newUser.setRole(registerRequest.role());
        } else {
            newUser.setRole("USER");
        }

        // active: default to true if not provided
        if (registerRequest.active() != null) {
            newUser.setActive(registerRequest.active());
        } else {
            newUser.setActive(true);
        }

        userRepository.save(newUser);

        return ResponseEntity.ok("User registered successfully.");
    }

    private boolean isValidEmail(String email) {
        // Simple regex for basic validation
        String regex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
        return Pattern.compile(regex).matcher(email).matches();
    }
}
