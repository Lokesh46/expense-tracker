package com.lokesh_codes.expense_tracker_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * Resolves the authenticated user.
 *
 * <p>Every service needs this and each used to re-implement it, which is how
 * ownership checks drift apart.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    public Integer requireId() {
        return require().getId();
    }
}
