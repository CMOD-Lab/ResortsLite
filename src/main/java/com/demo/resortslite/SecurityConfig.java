package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Security configuration that integrates Azure Active Directory (Entra ID) for authentication.
 *
 * Blocker-18 (cr-java-0090): Replaces file-based authentication with Azure Active Directory
 * using Microsoft Authentication Library (MSAL) and Spring Security Azure AD integration.
 *
 * @EnableRedisHttpSession activates Spring Session backed by Azure Cache for Redis,
 * replacing in-memory HttpSession storage (blockers 13-17, cr-java-0065).
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class SecurityConfig extends AadWebSecurityConfigurerAdapter {

    /**
     * Configures HTTP security to use Azure AD OAuth2 login.
     * All API endpoints require authentication via Azure AD tokens.
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http.authorizeRequests()
                // Allow H2 console access in development (disable in production)
                .antMatchers("/h2-console/**").permitAll()
                // All booking API endpoints require an authenticated Azure AD identity
                .antMatchers("/api/**").authenticated()
                .anyRequest().permitAll();

        // Allow H2 console frames in development
        http.headers().frameOptions().sameOrigin();
        // Disable CSRF for stateless REST API (tokens provide CSRF protection)
        http.csrf().disable();
    }
}
