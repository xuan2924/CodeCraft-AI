package ai.codecraft.identity;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class IdentitySecurity {
    private static final Logger LOG = LoggerFactory.getLogger(IdentitySecurity.class);

    @Bean
    Clock identityClock() { return Clock.systemUTC(); }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer identityJsonLimits() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(4096).maxNestingDepth(16).maxDocumentLength(16384).build()));
    }

    @Bean
    SecurityFilterChain identityChain(HttpSecurity http, IdentityStore store, IdentitySecrets secrets,
                                     IdentitySettings settings, ObjectMapper json) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()).cors(cors -> cors.disable())
            .headers(Customizer.withDefaults())
            .csrf(csrf -> csrf.csrfTokenRepository(new DatabaseCsrfRepository(store, secrets, settings))
                // IdentityStore rotates the database CSRF context only at actual login, not at every request authentication.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                    @Override
                    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                        return request.getHeader(IdentityHttp.CSRF_HEADER);
                    }
                }))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> IdentityHttp.error(response, json, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((request, response, error) -> IdentityHttp.error(response, json, 403,
                    error instanceof CsrfException ? "CSRF_REJECTED" : "FORBIDDEN")))
            .authorizeHttpRequests(routes -> routes
                .requestMatchers("/api/internal/status", "/api/auth/csrf", "/api/auth/login", "/api/auth/logout", "/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
                if (!"/api/internal/status".equals(request.getServletPath())) response.setHeader("Cache-Control", "no-store");
                boolean safe = List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
                if (!safe && !IdentityHttp.trustedSource(request, settings.publicOrigin())) {
                    IdentityHttp.error(response, json, 403, "CSRF_REJECTED");
                    return;
                }
                if (request.getContentLengthLong() > 16384) {
                    IdentityHttp.error(response, json, 413, "REQUEST_TOO_LARGE");
                    return;
                }
                try { chain.doFilter(request, response); }
                catch (IdentityFailure failure) {
                    IdentityHttp.error(response, json, failure.status(), failure.code());
                } catch (DataAccessException failure) {
                    // JDBC exceptions can include rejected rows containing secrets; never log their messages.
                    LOG.error("Identity database unavailable ({})", failure.getClass().getSimpleName());
                    IdentityHttp.error(response, json, 503, "IDENTITY_UNAVAILABLE");
                }
            }
        }, CsrfFilter.class);
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
                String path = request.getServletPath();
                if (!List.of("/api/internal/status", "/api/auth/csrf", "/api/auth/login", "/api/auth/logout", "/error").contains(path)) {
                    IdentityStore.Authenticated auth = store.authenticate(IdentityHttp.sessionHash(request));
                    if (auth.user().passwordChangeRequired()
                        && !List.of("/api/auth/session", "/api/auth/password").contains(path)) {
                        throw new IdentityFailure(403, "PASSWORD_CHANGE_REQUIRED");
                    }
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        auth.identity(), null, List.of(new SimpleGrantedAuthority("ROLE_" + auth.user().role()))));
                }
                chain.doFilter(request, response);
            }
        }, CsrfFilter.class);
        return http.build();
    }
}
