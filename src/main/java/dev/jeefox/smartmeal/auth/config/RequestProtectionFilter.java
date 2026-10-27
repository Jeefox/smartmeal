package dev.jeefox.smartmeal.auth.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Bounded, single-instance admission control. Client address comes from the socket, not request
 * headers.
 */
public final class RequestProtectionFilter extends OncePerRequestFilter {
    private final Map<String, Window> windows = new HashMap<>();
    private final Set<String> generating = new HashSet<>();
    private final Semaphore workers = new Semaphore(2);
    private final int authLimit, writeLimit;

    private record Window(long start, int count) {}

    public RequestProtectionFilter(int authLimit, int writeLimit) {
        if (authLimit < 1 || writeLimit < 1)
            throw new IllegalArgumentException("Rate limits must be positive");
        this.authLimit = authLimit;
        this.writeLimit = writeLimit;
    }

    private synchronized boolean admit(String key, int limit) {
        long now = System.nanoTime(), minute = 60_000_000_000L;
        var old = windows.get(key);
        if (old == null && windows.size() >= 10_000) {
            windows.values().removeIf(w -> now - w.start() >= minute);
            if (windows.size() >= 10_000) return false;
        }
        if (old == null || now - old.start() >= minute) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (old.count() >= limit) return false;
        windows.put(key, new Window(old.start(), old.count() + 1));
        return true;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.isEmpty())
            path = request.getRequestURI().substring(request.getContextPath().length());
        boolean write = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String owner = auth != null && auth.isAuthenticated() ? auth.getName() : null;
        boolean login = path.equals("/api/auth/login") || path.equals("/api/auth/register");
        if (write
                && !admit(
                        (login ? "auth:" : "write:") + request.getRemoteAddr(),
                        login ? authLimit : writeLimit)) {
            reject(response);
            return;
        }
        if (write && path.equals("/api/auth/login")) {
            String password = request.getParameter("password"),
                    email = request.getParameter("email");
            if ((password != null
                            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                    > 72)
                    || (email != null && email.length() > 100)) {
                response.setStatus(401);
                response.setHeader("Cache-Control", "no-store");
                return;
            }
        }
        if (write && path.equals("/api/auth/login")) {
            String email = request.getParameter("email");
            if (email != null && !email.isBlank()) {
                String key;
                try {
                    key =
                            java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            email.trim()
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)));
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException("SHA-256 is required", impossible);
                }
                if (!admit("login-account:" + key, authLimit)) {
                    reject(response);
                    return;
                }
            }
        }
        if (write && !login && owner != null && !admit("user:" + owner, writeLimit)) {
            reject(response);
            return;
        }
        if (write
                && path.matches("/api/recipes/[^/]+/images")
                && !admit("upload:" + request.getRemoteAddr(), 10)) {
            reject(response);
            return;
        }
        boolean heavy =
                owner != null
                        && ((path.startsWith("/api/me/meal-plan-generations")
                                        && (write || path.endsWith("/alternatives"))
                                        && !path.endsWith("/commit")
                                        && !path.endsWith("/lock"))
                                || path.startsWith("/api/me/meal-plans/preview")
                                || path.matches("/api/me/meal-plan-entries/[^/]+/replacements"));
        if (!heavy) {
            chain.doFilter(request, response);
            return;
        }
        synchronized (generating) {
            if (!generating.add(owner)) {
                reject(response);
                return;
            }
        }
        if (!workers.tryAcquire()) {
            synchronized (generating) {
                generating.remove(owner);
            }
            reject(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            workers.release();
            synchronized (generating) {
                generating.remove(owner);
            }
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.getWriter()
                .write(
                        "{\"status\":429,\"title\":\"Слишком много"
                            + " запросов\",\"detail\":\"Подождите минуту и повторите действие.\"}");
    }
}
