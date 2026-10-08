package cloud.jjoon.workout

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    }

    /** The same object storage as production, reached over its S3 API (architecture 10.1). */
    @Bean
    fun seaweedfsContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
            // SeaweedFS 4 rejects signed requests unless an S3 identity is configured.
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forListeningPorts(S3_PORT))

    @Bean
    fun storageProperties(seaweedfsContainer: GenericContainer<*>) = DynamicPropertyRegistrar { registry ->
        registry.add("storage.s3.endpoint") { "http://${seaweedfsContainer.host}:${seaweedfsContainer.getMappedPort(S3_PORT)}" }
        registry.add("storage.s3.access-key") { ACCESS_KEY }
        registry.add("storage.s3.secret-key") { SECRET_KEY }
    }

    companion object {
        private const val S3_PORT = 8333
        private const val ACCESS_KEY = "test-access"
        private const val SECRET_KEY = "test-secret"
        private val S3_CONFIG = """
            {"identities": [{"name": "test", "credentials": [{"accessKey": "$ACCESS_KEY", "secretKey": "$SECRET_KEY"}],
              "actions": ["Admin", "Read", "Write", "List", "Tagging"]}]}
        """.trimIndent()
    }
}
