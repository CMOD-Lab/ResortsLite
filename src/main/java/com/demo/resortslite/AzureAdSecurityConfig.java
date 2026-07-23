package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Azure Active Directory (Entra ID) Security Configuration.
 *
 * cr-java-0090: Replaces file-based authentication with Azure Active Directory
 * using Microsoft Authentication Library (MSAL) and Spring Security Azure AD
 * integration for centralized, scalable identity management.
 *
 * Authentication flow:
 *  1. Unauthenticated requests are redirected to Azure AD login.
 *  2. Azure AD issues a JWT token after successful authentication.
 *  3. Spring Security validates the JWT on every request — no local credential files needed.
 *  4. Credentials are never stored on the file system or in source code.
 *
 * Required environment variables / application properties:
 *   spring.cloud.azure.active-directory.enabled=true
 *   spring.cloud.azure.active-directory.credential.client-id=<AAD_CLIENT_ID>
 *   spring.cloud.azure.active-directory.credential.client-secret=<AAD_CLIENT_SECRET>
 *   spring.cloud.azure.active-directory.profile.tenant-id=<AAD_TENANT_ID>
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class AzureAdSecurityConfig extends AadWebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http.authorizeRequests()
                // Allow H2 console and health endpoints without authentication
                .antMatchers("/h2-console/**", "/actuator/health").permitAll()
                // All other API endpoints require authentication via Azure AD
                .anyRequest().authenticated();

        // Allow H2 console frames (development only)
        http.headers().frameOptions().sameOrigin();
        http.csrf().ignoringAntMatchers("/h2-console/**");
    }
}
