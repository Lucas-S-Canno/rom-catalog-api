package com.lucascanno.romcatalog.storage

import com.lucascanno.romcatalog.config.StorageConfig
import com.lucascanno.romcatalog.error.StorageUnavailableException
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import io.minio.http.Method
import okhttp3.OkHttpClient
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

interface StorageClient {
    /** Creates the configured bucket if it does not exist yet. Safe to call repeatedly. */
    fun ensureBucket()

    /** @return true if the object exists; false if it is simply absent. Throws [StorageUnavailableException] if the store is unreachable. */
    fun objectExists(key: String): Boolean

    /** Offline-signed GET URL. Its host comes from [StorageConfig.publicEndpoint]. */
    fun presignedGetUrl(key: String, ttl: Duration): String

    fun putObject(key: String, data: InputStream, size: Long, contentType: String)

    /** Opens the object for reading. Caller must close the stream. Throws [StorageUnavailableException] on any failure. */
    fun openObject(key: String): InputStream

    /** Cheap reachability probe for readiness checks — never creates anything. */
    fun bucketExists(): Boolean
}

class MinioStorageClient(
    /** Used for network operations against the storage. */
    private val controlClient: MinioClient,
    /** Used only to *sign* URLs — never makes a network call. Points at the public endpoint. */
    private val signingClient: MinioClient,
    private val bucket: String,
) : StorageClient {

    override fun ensureBucket() {
        try {
            val exists = controlClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                controlClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            }
        } catch (e: Exception) {
            throw StorageUnavailableException("Could not ensure bucket '$bucket'", e)
        }
    }

    override fun objectExists(key: String): Boolean {
        return try {
            controlClient.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build())
            true
        } catch (e: ErrorResponseException) {
            val code = e.errorResponse()?.code()
            if (code == "NoSuchKey" || code == "NoSuchObject" || code == "ResourceNotFound") {
                false
            } else {
                throw StorageUnavailableException("statObject failed for '$key': $code", e)
            }
        } catch (e: Exception) {
            throw StorageUnavailableException("Storage unreachable while checking '$key'", e)
        }
    }

    override fun presignedGetUrl(key: String, ttl: Duration): String {
        return try {
            signingClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .`object`(key)
                    .expiry(ttl.seconds.toInt(), TimeUnit.SECONDS)
                    .build()
            )
        } catch (e: Exception) {
            throw StorageUnavailableException("Could not sign download URL for '$key'", e)
        }
    }

    override fun putObject(key: String, data: InputStream, size: Long, contentType: String) {
        try {
            controlClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(key)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build()
            )
        } catch (e: Exception) {
            throw StorageUnavailableException("Could not upload object '$key'", e)
        }
    }

    override fun openObject(key: String): InputStream {
        return try {
            controlClient.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build())
        } catch (e: Exception) {
            throw StorageUnavailableException("Could not read object '$key'", e)
        }
    }

    override fun bucketExists(): Boolean {
        return try {
            controlClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
        } catch (e: Exception) {
            throw StorageUnavailableException("Storage unreachable while probing bucket '$bucket'", e)
        }
    }

    companion object {
        fun create(config: StorageConfig): MinioStorageClient {
            val timeout = Duration.ofMillis(config.timeoutMs)
            val http = OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build()
            val control = MinioClient.builder()
                .endpoint(config.endpoint)
                .region(config.region)
                .credentials(config.accessKey, config.secretKey)
                .httpClient(http)
                .build()
            val signing = MinioClient.builder()
                .endpoint(config.publicEndpoint)
                .region(config.region)
                .credentials(config.accessKey, config.secretKey)
                .build()
            return MinioStorageClient(control, signing, config.bucket)
        }
    }
}
