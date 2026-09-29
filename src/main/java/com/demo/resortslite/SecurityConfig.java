package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * SecurityConfig — Azure Active Directory (Entra ID) Resource Server configuration.
 *
 * cr-java-0090 (File-based Authentication):
 * This class replaces the previous file-based authentication pattern where credentials,
 * user data, and security tokens were stored in local files or in-memory structures
 * (e.g., MD5-hashed tokens derived from locally stored credentials).
 *
 * Cloud-native replacement strategy:
 *   • Extends {@link AadResourceServerWebSecurityConfigurerAdapter} provided by the
 *     Spring Cloud Azure Active Directory starter (spring-cloud-azure-starter-active-directory).
 *   • Configures Spring Security to act as an OAuth2 Resource Server that validates
 *     Bearer JWT tokens issued by Azure Active Directory (Entra ID).
 *   • All authentication and authorization decisions are delegated to Azure AD:
 *       - Token issuance, signing, and validation are handled by Azure AD.
 *       - User identities (oid, upn, roles) are carried in the AAD-signed JWT.
 *       - No credentials, password hashes, or tokens are stored locally.
 *   • Supports Azure Managed Identity for service-to-service authentication,
 *     eliminating the need for any stored secrets in the application.
 *
 * Configuration is driven entirely by environment variables (see application.properties):
 *   AZURE_AD_TENANT_ID     — Azure AD tenant identifier
 *   AZURE_AD_CLIENT_ID     — Registered application (client) ID in Azure AD
 *   AZURE_AD_CLIENT_SECRET — Client secret (stored in Azure Key Vault, not source code)
 *
 * Endpoints secured:
 *   • /api/bookings/**  — requires a valid Azure AD Bearer token
 *   • /h2-console/**    — permitted for local development only (disable in production)
 *   • /actuator/health  — permitted for Azure load-balancer health probes
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends AadResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures the HTTP security filter chain to:
     *   1. Validate all requests to /api/bookings/** with Azure AD JWT Bearer tokens.
     *   2. Allow unauthenticated access to health-check and H2 console endpoints
     *      (H2 console access should be disabled or restricted in production).
     *   3. Disable CSRF for stateless REST API (tokens provide CSRF protection).
     *   4. Disable frame options for H2 console (development only).
     *
     * The parent class {@link AadResourceServerWebSecurityConfigurerAdapter#configure(HttpSecurity)}
     * registers the Azure AD JWT decoder and the OAuth2 resource-server filter chain,
     * which validates the Bearer token's signature, issuer, audience, and expiry against
     * Azure Active Directory's OIDC discovery endpoint on every request.
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Invoke parent to register Azure AD JWT validation filter chain.
        super.configure(http);

        http
            // Disable CSRF — stateless REST API protected by Azure AD Bearer tokens.
            .csrf().disable()
            // Disable X-Frame-Options for H2 console (development only).
            .headers().frameOptions().disable()
            .and()
            .authorizeRequests()
                // Allow health-check endpoint for Azure load-balancer probes.
                .antMatchers("/actuator/health", "/actuator/info").permitAll()
                // Allow H2 console for local development (restrict or remove in production).
                .antMatchers("/h2-console/**").permitAll()
                // All booking API endpoints require a valid Azure AD Bearer token.
                // The token must be issued by the configured tenant and target the
                // registered application's client ID as the audience.
                .antMatchers("/api/bookings/**").authenticated()
                // Any other request also requires authentication.
                .anyRequest().authenticated();
    }
}
