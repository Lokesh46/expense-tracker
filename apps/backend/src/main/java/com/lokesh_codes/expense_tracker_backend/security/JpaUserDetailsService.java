package com.lokesh_codes.expense_tracker_backend.security;

import java.util.Optional;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.entity.User;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public JpaUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Optional<User> userOptional = userRepository.findByUsername(username);

        if (userOptional.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        User user = userOptional.get();
        Role role = user.getRole() == null ? Role.MEMBER : user.getRole();

        // The stored value is a BCrypt hash written by the registration endpoint,
        // so it is handed over untouched and verified by the configured
        // PasswordEncoder. Prefixing it with "{noop}" (as this once did) told
        // Spring Security to treat the hash as plaintext, which made every
        // login fail.
        //
        // accountLocked and disabled are reported here rather than checked in the
        // controller, so Spring's own pre-authentication checks raise
        // LockedException and DisabledException. Those are distinguishable from a
        // wrong password, which is what lets the sign-in endpoint explain itself.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles(role.name())
                .accountLocked(user.isLocked())
                .disabled(!user.isActive())
                .build();
    }
}
