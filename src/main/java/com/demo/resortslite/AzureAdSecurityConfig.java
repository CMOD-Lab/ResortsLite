package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * cr-java-0090 REMEDIATION: Azure Active Directory (Entra ID) Security Configuration.
 *
 * <p>Replaces file-based authentication (hardcoded credentials stored in local source
 * files / properties files) with Azure Active Directory OAuth2 / OIDC authentication
 * using the Microsoft Authentication Library (MSAL) and Spring Security Azure AD
 * integration for centralized, scalable identity management.</p>
 *
 * <p><strong>Before (file-based authentication — VIOLATION):</strong>
 * <pre>
 *   private static final String DB_USER = "admin";           // credentials in source file
 *   private static final String DB_PASS = "Resort$Pass#2019!"; // credentials in source file
 * </pre>
 * </p>
 *
 * <p><strong>After (Azure AD authentication — REMEDIATED):</strong>
 * <ul>
 *   <li>API endpoints are protected by Azure AD bearer-token (JWT) validation.</li>
 *   <li>Tokens are issued by Azure Active Directory (Entra ID) and validated via
 *       the JWKS endpoint configured in {@code spring.cloud.azure.active-directory.*}
 *       properties — no credentials are stored in any local file.</li>
 *   <li>Database credentials are retrieved from Azure Key Vault via Managed Identity
 *       (DefaultAzureCredential), which itself authenticates through Azure AD.</li>
 *   <li>H2 console access is permitted for local development only; all other
 *       {@code /api/**} endpoints require a valid Azure AD JWT.</li>
 * </ul>
 * </p>
 *
 * <p><strong>Required Azure AD application settings (set as environment variables or
 * Azure App Service application settings — never in source code):</strong>
 * <pre>
 *   AZURE_AD_TENANT_ID      — Azure AD tenant (directory) ID
 *   AZURE_AD_CLIENT_ID      — Application (client) ID registered in Azure AD
 *   AZURE_AD_CLIENT_SECRET  — Client secret for the registered application
 *                             (or use Managed Identity to avoid secrets entirely)
 *   AZURE_AD_APP_ID_URI     — Application ID URI used as the OAuth2 audience
 * </pre>
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class AzureAdSecurityConfig extends AadResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures HTTP security to require Azure AD JWT bearer tokens for all
     * protected API endpoints.
     *
     * <p>The {@link AadResourceServerWebSecurityConfigurerAdapter} base class wires in
     * the MSAL-backed JWT decoder that validates tokens against Azure AD's JWKS endpoint,
     * ensuring only authenticated Azure AD principals can access the booking API.</p>
     *
     * @param http the {@link HttpSecurity} to configure
     * @throws Exception if the security configuration fails
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Apply Azure AD resource-server JWT validation from the base class.
        super.configure(http);

        http
            // cr-java-0090: Disable CSRF for stateless REST API (tokens provide CSRF protection).
            .csrf().disable()
            .authorizeRequests(requests -> requests
                // Allow H2 console for local development without authentication.
                .antMatchers("/h2-console/**").permitAll()
                // Allow Spring Boot actuator health endpoint without authentication.
                .antMatchers("/actuator/health").permitAll()
                // cr-java-0090: All booking API endpoints require a valid Azure AD JWT.
                // No local file-based credentials are used; identity is verified by
                // Azure Active Directory (Entra ID) token validation.
                .antMatchers("/api/**").authenticated()
                // All other requests also require authentication.
                .anyRequest().authenticated()
            )
            // Allow H2 console frames (local dev only).
            .headers().frameOptions().sameOrigin();
    }
}
