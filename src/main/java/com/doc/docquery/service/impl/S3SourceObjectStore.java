package com.doc.docquery.service.impl;

import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.SourceObjectStore;
import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
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
import java.util.List;

/** AWS SDK v2 实现的 S3 兼容原文件适配器。 */
public class S3SourceObjectStore implements SourceObjectStore {

    private final S3Client s3Client;
    private final String bucket;

    public S3SourceObjectStore(
            S3Client s3Client,
            ObjectStorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.bucket = properties.getBucket();
    }

    /** 启动时只校验 Bucket，不在生产环境隐式创建基础设施。 */
    @PostConstruct
    void verifyBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Source object bucket is unavailable", exception);
        }
    }

    @Override
    public String bucketName() {
        return bucket;
    }

    @Override
    public WriteResult put(
            String objectKey,
            InputStream input,
            long declaredSize,
            long maxBytes,
            String contentType
    ) {
        if (declaredSize < 1) {
            throw new ObjectStorageException(
                    ObjectStorageException.Reason.UNAVAILABLE,
                    "Source object is empty"
            );
        }
        if (declaredSize > maxBytes) {
            throw tooLarge();
        }

        CountingDigestInputStream measured = new CountingDigestInputStream(
                input,
                maxBytes
        );
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }

        try {
            s3Client.putObject(
                    request.build(),
                    RequestBody.fromInputStream(measured, declaredSize)
            );
            if (measured.count() != declaredSize) {
                throw new ObjectStorageException(
                        ObjectStorageException.Reason.UNAVAILABLE,
                        "Stored source size does not match upload size"
                );
            }
            return new WriteResult(measured.count(), measured.sha256());
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (S3Exception | SdkClientException exception) {
            if (containsTooLarge(exception)) {
                throw tooLarge();
            }
            throw unavailable("Source object could not be stored", exception);
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
            throw unavailable("Source object could not be read", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()
            );
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw unavailable("Source object existence could not be checked", exception);
        } catch (SdkClientException exception) {
            throw unavailable("Source object existence could not be checked", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build()
            );
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Source object could not be deleted", exception);
        }
    }

    @Override
    public List<ObjectSummary> list(String prefix, int limit) {
        try {
            return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .maxKeys(limit)
                            .build())
                    .contents()
                    .stream()
                    .map(item -> new ObjectSummary(item.key(), item.lastModified()))
                    .toList();
        } catch (S3Exception | SdkClientException exception) {
            throw unavailable("Source objects could not be listed", exception);
        }
    }

    private ObjectStorageException unavailable(String message, Throwable cause) {
        return new ObjectStorageException(
                ObjectStorageException.Reason.UNAVAILABLE,
                message,
                cause
        );
    }

    private ObjectStorageException tooLarge() {
        return new ObjectStorageException(
                ObjectStorageException.Reason.FILE_TOO_LARGE,
                "Source object exceeds upload limit"
        );
    }

    private boolean containsTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UploadLimitExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 同一遍网络读取同时完成计数和摘要，不把 50 MiB 文件整体放入 JVM。 */
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
                throw new UploadLimitExceededException();
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

    /** 只在 SDK 读取流超过业务上限时使用，不把底层异常文本返回给客户端。 */
    private static final class UploadLimitExceededException extends IOException {
    }
}
