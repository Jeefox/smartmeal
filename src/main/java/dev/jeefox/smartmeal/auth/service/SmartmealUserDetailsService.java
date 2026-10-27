package dev.jeefox.smartmeal.auth.service;

import dev.jeefox.smartmeal.user.entity.User;
import dev.jeefox.smartmeal.user.repository.UserRepository;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class SmartmealUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;
    private final dev.jeefox.smartmeal.auth.config.CatalogueEditors editors;

    public SmartmealUserDetailsService(
            UserRepository userRepository,
            dev.jeefox.smartmeal.auth.config.CatalogueEditors editors) {
        this.userRepository = userRepository;
        this.editors = editors;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user =
                userRepository
                        .findByEmail(username.trim())
                        .orElseThrow(
                                () -> new UsernameNotFoundException("Неверный email или пароль"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles(
                        editors.contains(user.getId())
                                ? new String[] {"USER", "CATALOG_EDITOR"}
                                : new String[] {"USER"})
                .build();
    }
}
