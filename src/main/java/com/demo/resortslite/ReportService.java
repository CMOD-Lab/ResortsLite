package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Blocker-1/2/3 (cr-java-0061): Hard-coded file paths replaced.
    // Blocker-4 (cr-java-0062): Local file write replaced with Amazon S3.
    // Blocker-5/6/7 (cr-java-0063): java.io.File usage replaced with S3 client.
    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable.
    // Blocker-11 (cr-java-0071): Hard-coded URL replaced with AWS SSM Parameter Store.
    // Blocker-19 (cr-java-0111): java.util.Date replaced with java.time API (UTC).

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    // S3 bucket name and key prefix injected via environment variables (12-factor)
    @Value("${cloud.aws.s3.report-bucket:resorts-reports-bucket}")
    private String reportBucket;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // Server port injected via environment variable — no hard-coded port (blocker-12)
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // SSM parameter name for the report download base URL (blocker-11)
    @Value("${cloud.aws.ssm.report-url-param:/resortslite/report/download-url}")
    private String reportUrlSsmParam;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Blocker-1 (cr-java-0061): S3 key replaces hard-coded /var/legacy/reports/ path
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-4 (cr-java-0062) & Blocker-5/6/7 (cr-java-0063):
            // Replace java.io.File + FileWriter with Amazon S3 PutObject
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            // Blocker-12 (cr-java-0077): port sourced from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Retrieve base URL from AWS SSM Parameter Store
        // instead of using a hard-coded environment-specific URL
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportUrlSsmParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + "/download/" + reportName;
        } catch (Exception e) {
            // Fallback: construct S3 pre-signed URL path reference
            return "https://" + reportBucket + ".s3.amazonaws.com/" + reportPrefix + reportName;
        }
    }

    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Replace java.util.Date / SimpleDateFormat with
        // java.time API standardised on UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3 (cr-java-0061): report location is now an S3 bucket/prefix
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        // Blocker-12 (cr-java-0077): port from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
