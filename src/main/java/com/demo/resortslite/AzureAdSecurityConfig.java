package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * AzureAdSecurityConfig — Spring Security configuration for Azure Active Directory
 * (Entra ID) JWT-based authentication.
 *
 * cr-java-0090 REMEDIATION: Replaces file-based authentication (local credential
 * storage and MD5 hashing) with Azure Active Directory (Entra ID) authentication
 * using Microsoft Authentication Library (MSAL) and Spring Security OAuth2
 * Resource Server for centralised, scalable identity management.
 *
 * How it works:
 *   1. The application is registered as an Azure AD App Registration (resource server).
 *   2. Clients obtain a Bearer JWT access token from Azure AD (Entra ID) using MSAL
 *      or the OAuth2 authorization code / client credentials flow.
 *   3. Every incoming HTTP request must include the Bearer token in the Authorization header.
 *   4. Spring Security validates the JWT signature and claims (iss, aud, exp) against
 *      the Azure AD JWKS endpoint derived from:
 *        spring.security.oauth2.resourceserver.jwt.issuer-uri
 *      (configured in application.properties via the AZURE_AD_TENANT_ID env variable).
 *   5. The validated JWT principal (OID, UPN, roles) is available throughout the
 *      application via SecurityContextHolder — no local credential state is maintained.
 *
 * Session management is set to STATELESS so that no server-side HTTP session is
 * created for authentication, enabling true horizontal scalability in Azure.
 *
 * Public endpoints (H2 console, health checks) are permitted without authentication
 * to support local development and Azure health probe requirements.
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class AzureAdSecurityConfig {

    /**
     * Configures the Spring Security filter chain to use Azure AD JWT Bearer token
     * authentication as the sole authentication mechanism.
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be applied
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — not required for stateless JWT-based REST APIs
            .csrf().disable()

            // Stateless session management: no HttpSession is created or used for auth.
            // Authentication state is carried entirely in the Azure AD JWT Bearer token.
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()

            // Authorization rules
            .authorizeRequests()
                // Allow H2 console and actuator health endpoint without authentication
                // (required for local development and Azure App Service health probes)
                .antMatchers("/h2-console/**", "/actuator/health").permitAll()
                // All other endpoints require a valid Azure AD JWT Bearer token
                .anyRequest().authenticated()
            .and()

            // Configure as OAuth2 Resource Server — validates Azure AD JWT Bearer tokens.
            // The issuer URI is set via spring.security.oauth2.resourceserver.jwt.issuer-uri
            // in application.properties (derived from AZURE_AD_TENANT_ID env variable).
            .oauth2ResourceServer()
                .jwt();

        // Allow H2 console frames (local development only — disable in production)
        http.headers().frameOptions().sameOrigin();

        return http.build();
    }
}
