package com.lucascanno.romcatalog.support

import com.lucascanno.romcatalog.config.DatabaseConfig
import com.lucascanno.romcatalog.config.StorageConfig
import com.lucascanno.romcatalog.db.DatabaseFactory
import com.lucascanno.romcatalog.db.DatabaseResources
import com.lucascanno.romcatalog.storage.MinioStorageClient
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Container-backed infrastructure shared by every integration/route test.
 * Containers start on first use and are torn down by Testcontainers' Ryuk at the
 * end of the JVM — one Postgres and one MinIO for the whole suite.
 */
object TestInfra {

    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16"))
            .withDatabaseName("romcatalog")
            .withUsername("romcatalog")
            .withPassword("romcatalog")
            .also { it.start() }
    }

    val minio: MinIOContainer by lazy {
        MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2023-09-04T19-57-37Z"))
            .also { it.start() }
    }

    const val BUCKET = "roms"

    val db: DatabaseResources by lazy {
        DatabaseFactory.init(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password,
            )
        )
    }

    fun storageConfig(bucket: String = BUCKET) = StorageConfig(
        endpoint = minio.s3URL,
        publicEndpoint = minio.s3URL,
        accessKey = minio.userName,
        secretKey = minio.password,
        bucket = bucket,
    )

    val storage: MinioStorageClient by lazy {
        MinioStorageClient.create(storageConfig()).also { it.ensureBucket() }
    }

    private val rawMinio: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(minio.s3URL)
            .credentials(minio.userName, minio.password)
            .build()
    }

    fun truncateAll() {
        transaction(db.database) {
            exec("TRUNCATE TABLE favorites, roms RESTART IDENTITY CASCADE")
        }
    }

    /** Executes a statement for its side effect. */
    fun execute(sql: String) {
        transaction(db.database) { exec(sql) }
    }

    /** Runs a read-only query and maps every row. */
    fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> = transaction(db.database) {
        val out = mutableListOf<T>()
        exec(sql) { rs -> while (rs.next()) out.add(map(rs)) }
        out
    }

    fun clearBucket(bucket: String = BUCKET) {
        // touch `storage` first so the bucket exists
        storage
        for (key in objectKeys(bucket)) {
            rawMinio.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
        }
    }

    fun objectKeys(bucket: String = BUCKET): List<String> {
        storage
        return rawMinio.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())
            .map { it.get().objectName() }
    }
}
