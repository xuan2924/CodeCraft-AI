package ai.codecraft.modules.identity.security;

import ai.codecraft.common.exception.ApiErrorWriter;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.model.dto.AuthenticatedIdentityDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.common.web.RequestBodyLimitException;
import org.springframework.http.MediaType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    SecurityFilterChain identityChain(HttpSecurity http, IdentityTransactionService store, IdentitySecrets secrets,
                                     IdentitySettings settings, ObjectMapper json, RequestBodyAdmission bodies) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()).cors(cors -> cors.disable())
            .headers(Customizer.withDefaults())
            .csrf(csrf -> csrf.csrfTokenRepository(new DatabaseCsrfRepository(store, secrets, settings))
                // The transaction service rotates CSRF only at actual login, not at every request authentication.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                    @Override
                    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                        return request.getHeader(IdentityHttp.CSRF_HEADER);
                    }
                }))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> ApiErrorWriter.write(response, json, IdentityErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((request, response, error) -> ApiErrorWriter.write(response, json,
                    error instanceof CsrfException ? IdentityErrorCode.CSRF_REJECTED : IdentityErrorCode.FORBIDDEN)))
            .authorizeHttpRequests(routes -> routes
                .requestMatchers("/api/internal/status", "/api/auth/csrf", "/api/auth/captcha", "/api/auth/login", "/api/auth/logout", "/error").permitAll()
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
                    ApiErrorWriter.write(response, json, IdentityErrorCode.CSRF_REJECTED);
                    return;
                }
                if (!safe && request.getContentType() != null) {
                    try {
                        MediaType type = MediaType.parseMediaType(request.getContentType());
                        if (!MediaType.APPLICATION_JSON.isCompatibleWith(type) && !MediaType.valueOf("application/*+json").isCompatibleWith(type)) {
                            ApiErrorWriter.write(response, json, IdentityErrorCode.INVALID_REQUEST);
                            return;
                        }
                    } catch (IllegalArgumentException malformed) {
                        ApiErrorWriter.write(response, json, IdentityErrorCode.INVALID_REQUEST);
                        return;
                    }
                }
                try (var admission = bodies.admit(request, bodies.maximumBytes(request))) {
                    chain.doFilter(admission.request(), response);
                } catch (RequestBodyLimitException failure) {
                    ApiErrorWriter.write(response, json, failure.errorCode());
                } catch (BusinessException failure) {
                    ApiErrorWriter.write(response, json, failure.errorCode());
                } catch (DataAccessException failure) {
                    // JDBC exceptions can include rejected rows containing secrets; never log their messages.
                    LOG.error("Identity database unavailable ({})", failure.getClass().getSimpleName());
                    ApiErrorWriter.write(response, json, IdentityErrorCode.IDENTITY_UNAVAILABLE);
                }
            }
        }, CsrfFilter.class);
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
                String path = request.getServletPath();
                if (!List.of("/api/internal/status", "/api/auth/csrf", "/api/auth/captcha", "/api/auth/login", "/api/auth/logout", "/error").contains(path)) {
                    AuthenticatedIdentityDTO auth = store.authenticate(IdentityHttp.sessionHash(request));
                    // Optional client ownership condition: a stale tab must not read or submit secrets under a newly switched cookie.
                    String expectedOwner = request.getHeader(IdentityHttp.EXPECTED_OWNER_HEADER);
                    if (expectedOwner != null && !auth.identity().id().equals(expectedOwner)) {
                        throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
                    }
                    boolean ownRevocation = auth.user().passwordChangeRequired() && request.getMethod().equals("GET")
                        && path.matches("/api/operations/[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                        && store.revocation(IdentityHttp.sessionHash(request), path.substring("/api/operations/".length())) != null;
                    if (auth.user().passwordChangeRequired() && !ownRevocation
                        && !List.of("/api/auth/session", "/api/auth/password").contains(path)) {
                        throw new IdentityFailure(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
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
