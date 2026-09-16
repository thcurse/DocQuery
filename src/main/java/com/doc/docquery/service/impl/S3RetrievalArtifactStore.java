package com.doc.docquery.service.impl;

import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.service.ObjectListingPage;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.RetrievalArtifactStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 使用标准 S3 操作保存 retrieval JSONL，不泄漏 SeaweedFS 专有 API。 */
public class S3RetrievalArtifactStore implements RetrievalArtifactStore {

    private final S3Client s3Client;
    private final String bucket;

    public S3RetrievalArtifactStore(S3Client s3Client, ObjectStorageProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.getBucket();
    }

    @Override
    public String bucketName() {
        return bucket;
    }

    @Override
    public WriteResult put(String objectKey, InputStream input, long sizeBytes, long maxBytes) {
        if (sizeBytes < 1 || sizeBytes > maxBytes) {
            throw new ObjectStorageException(
                    ObjectStorageException.Reason.FILE_TOO_LARGE,
                    "Retrieval object exceeds storage limit"
            );
        }
        CountingDigestInputStream measured = new CountingDigestInputStream(input, maxBytes);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("application/x-ndjson")
                            .build(),
                    RequestBody.fromInputStream(measured, sizeBytes)
            );
            if (measured.count() != sizeBytes) {
                throw unavailable("Retrieval object size does not match", null);
            }
            return new WriteResult(measured.count(), measured.sha256());
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Retrieval object could not be stored", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
            );
            return response;
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Retrieval object could not be read", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(objectKey).build());
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw unavailable("Retrieval object existence could not be checked", exception);
        } catch (SdkClientException exception) {
            throw unavailable("Retrieval object existence could not be checked", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(objectKey).build());
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Retrieval object could not be deleted", exception);
        }
    }

    @Override
    public ObjectListingPage<ObjectSummary> listPage(
            String prefix, int limit, String continuationToken
    ) {
        try {
            var response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .maxKeys(Math.max(1, Math.min(limit, 1_000)))
                            .continuationToken(continuationToken)
                            .build());
            String nextToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken() : null;
            if (Boolean.TRUE.equals(response.isTruncated())
                    && (nextToken == null || nextToken.isBlank()
                    || nextToken.equals(continuationToken))) {
                throw unavailable("Retrieval object listing did not advance", null);
            }
            return new ObjectListingPage<>(response.contents().stream()
                    .map(item -> new ObjectSummary(item.key(), item.lastModified()))
                    .toList(), nextToken);
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Retrieval objects could not be listed", exception);
        }
    }

    private ObjectStorageException unavailable(String message, Throwable cause) {
        return new ObjectStorageException(
                ObjectStorageException.Reason.UNAVAILABLE,
                message,
                cause
        );
    }

    /** 读取上传流时同步限制大小并计算实际摘要。 */
    private static final class CountingDigestInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private final long limit;
        private long count;

        private CountingDigestInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(new byte[]{(byte) value}, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                record(buffer, offset, read);
            }
            return read;
        }

        private void record(byte[] buffer, int offset, int length) throws IOException {
            count += length;
            if (count > limit) {
                throw new IOException("Retrieval object exceeds configured limit");
            }
            digest.update(buffer, offset, length);
        }

        private long count() {
            return count;
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
