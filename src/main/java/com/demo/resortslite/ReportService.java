package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.HashMap;
import java.util.Map;
import java.time.Instant;

@Service
public class ReportService {

    // cr-java-0063 fix: Replaced hard-coded absolute path "/var/legacy/reports/"
    // (original: private static final String REPORT_BASE_PATH = "/var/legacy/reports/")
    // with S3 bucket name sourced from environment variable. Local file system paths are
    // ephemeral in containerised/cloud environments; Amazon S3 provides durable, scalable storage.
    @Value("${cloud.aws.s3.bucket-name:resorts-reports-bucket}")
    private String s3BucketName;

    // cr-java-0063 fix: Replaced hard-coded Windows-style path
    // (original: private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\")
    // with S3 backup prefix sourced from environment variable.
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    // cr-java-0077 fix: Replaced hard-coded port constant (original: private static final int SERVER_PORT = 8080)
    // with a Spring @Value-injected field that reads from the SERVER_PORT environment variable.
    // In AWS deployments (ECS task definitions, EKS pod specs, Elastic Beanstalk environment
    // properties), set the SERVER_PORT environment variable to the desired port value.
    // The canonical port value is stored in AWS Systems Manager Parameter Store under
    // /resortslite/server/port and injected at runtime via ECS task definition environment
    // variable injection, enabling dynamic port assignment required by container orchestration
    // platforms and cloud service discovery mechanisms without rebuilding the container image.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 fix: Replaced local file path construction (REPORT_BASE_PATH + fileName)
        // with an S3 object key. Data written to S3 is durable across container restarts and
        // scales horizontally without shared-filesystem dependencies.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 fix (lines 37-46 in original source):
            // Occurrence 1 (line 37): Replaced "new File(REPORT_BASE_PATH)" — java.io.File
            //   instantiation for directory creation removed; S3 bucket is the target store.
            // Occurrence 2 (line 39): Replaced "reportDir.mkdirs()" — directory creation on
            //   local filesystem removed; S3 object keys do not require pre-created directories.
            // Occurrence 3 (line 42): Replaced "new FileWriter(fullPath)" and subsequent
            //   writer.write() / writer.close() calls with Amazon S3 PutObject call.
            // Amazon S3 provides durable, highly-available object storage that persists data
            // independently of container lifecycle, preventing data loss on container restarts
            // or scale-in events.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // cr-java-0077 fix: Use injected serverPort field instead of hard-coded constant
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 fix: Replaced java.util.Date + SimpleDateFormat (server-local timezone)
        // with java.time.Instant.now() standardized on UTC. In distributed cloud environments
        // (multi-region AWS deployments, ECS/EKS containers), each node may run in a different
        // timezone; using Instant.now().toString() always produces an ISO-8601 UTC timestamp,
        // eliminating timezone inconsistencies across services and regions.
        String timestamp = Instant.now().toString();
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 fix: Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH
        // local filesystem references with S3 bucket/prefix references.
        info.put("s3BucketName", s3BucketName);
        info.put("s3BackupPrefix", s3BackupPrefix);
        // cr-java-0077 fix: Use injected serverPort field instead of hard-coded constant
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
