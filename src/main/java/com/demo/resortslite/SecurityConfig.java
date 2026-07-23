package com.demo.resortslite;

import com.azure.spring.aad.webapi.AADResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Security configuration — Azure Active Directory (Entra ID) integration.
 *
 * <p>cr-java-0090 FIX: Replaces file-based credential storage with Azure Active
 * Directory (Entra ID) authentication using Microsoft Authentication Library (MSAL)
 * and Spring Security Azure AD integration for centralized, scalable identity
 * management. No credentials are read from local files.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code AZURE_TENANT_ID} — Azure AD tenant identifier</li>
 *   <li>{@code AZURE_CLIENT_ID} — registered application (client) ID</li>
 *   <li>{@code AZURE_CLIENT_SECRET} — client secret (stored in Azure Key Vault)</li>
 * </ul>
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends AADResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures HTTP security to use Azure AD OAuth2 resource server authentication.
     * All API endpoints require a valid Azure AD bearer token; the H2 console and
     * health endpoints are permitted without authentication for development convenience.
     *
     * @param http the {@link HttpSecurity} to configure
     * @throws Exception if configuration fails
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http.authorizeRequests()
                // Allow H2 console and actuator health without authentication
                .antMatchers("/h2-console/**", "/actuator/health").permitAll()
                // All other API endpoints require a valid Azure AD token
                .anyRequest().authenticated();

        // Allow H2 console frames (development only — disable in production)
        http.headers().frameOptions().sameOrigin();
        http.csrf().ignoringAntMatchers("/h2-console/**");
    }
}
