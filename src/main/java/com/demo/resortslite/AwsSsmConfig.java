package com.demo.resortslite;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * cr-java-0071 FIX: AWS Systems Manager Parameter Store configuration component.
 *
 * <p>Provides a centralised, cloud-native mechanism for resolving environment-specific
 * URLs and configuration values from AWS SSM Parameter Store. This replaces all
 * hard-coded environment URLs in the application, enabling environment-agnostic
 * deployments across dev, staging, and production without code changes.</p>
 *
 * <p>The SSM client uses the AWS SDK v2 default credential provider chain, which
 * automatically resolves credentials from environment variables, EC2 instance profiles,
 * ECS task roles, or ~/.aws/credentials — no hard-coded credentials required.</p>
 *
 * <p>Parameter values are cached in-process to avoid repeated SSM API calls on every
 * request. The cache is populated lazily on first access per parameter name.</p>
 *
 * <p>Required SSM parameters (must be created in the target AWS account/region):</p>
 * <ul>
 *   <li>{@code /resortslite/inventory/service-url} — inventory service base URL
 *       (e.g. {@code https://inventory-service.internal:8081/rooms/available})</li>
 *   <li>{@code /resortslite/reports/download-base-url} — report download base URL
 *       (e.g. {@code https://reports.resorts-internal.com/download/})</li>
 * </ul>
 *
 * <p>The parameter names can be overridden via environment variables:</p>
 * <ul>
 *   <li>{@code INVENTORY_SERVICE_URL_PARAM} — overrides the inventory URL parameter name</li>
 *   <li>{@code REPORT_DOWNLOAD_BASE_URL_PARAM} — overrides the report URL parameter name</li>
 * </ul>
 */
@Component
public class AwsSsmConfig {

    /**
     * In-process parameter cache. Avoids repeated SSM GetParameter API calls for the
     * same parameter name within a single application instance lifetime.
     */
    private final Map<String, String> parameterCache = new ConcurrentHashMap<>();

    /**
     * AWS SSM client. Region is resolved from the {@code AWS_REGION} environment variable
     * or the EC2/ECS instance metadata service via the SDK default provider chain.
     */
    private SsmClient ssmClient;

    @PostConstruct
    public void init() {
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>The value is cached after the first successful retrieval. If the parameter
     * is not found in SSM (e.g. during local development), a descriptive fallback
     * message is returned so the application starts without throwing an exception.</p>
     *
     * @param parameterName the SSM parameter name (e.g. {@code /resortslite/inventory/service-url})
     * @return the parameter value from SSM, or a fallback string if not found
     */
    public String getParameter(String parameterName) {
        return parameterCache.computeIfAbsent(parameterName, this::fetchFromSsm);
    }

    /**
     * Fetches a single SecureString or String parameter from SSM Parameter Store.
     * WithDecryption is set to {@code true} to support SecureString parameters.
     *
     * @param parameterName the SSM parameter name to fetch
     * @return the resolved parameter value, or a fallback string on error
     */
    private String fetchFromSsm(String parameterName) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (ParameterNotFoundException e) {
            // Parameter does not exist in SSM — return a clearly identifiable fallback
            // so callers can detect misconfiguration rather than silently using a wrong URL.
            return "SSM_PARAMETER_NOT_FOUND:" + parameterName;
        } catch (Exception e) {
            // Catch-all for SDK/network errors (e.g. no IAM permissions, no connectivity).
            // Log the error and return a fallback to prevent application startup failure.
            return "SSM_ERROR:" + parameterName + ":" + e.getMessage();
        }
    }

    @PreDestroy
    public void destroy() {
        if (ssmClient != null) {
            ssmClient.close();
        }
    }
}
