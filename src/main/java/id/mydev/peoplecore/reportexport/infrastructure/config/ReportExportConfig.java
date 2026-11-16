package id.mydev.peoplecore.reportexport.infrastructure.config;

import id.mydev.peoplecore.document.application.service.RevocableDocumentTransfer;
import id.mydev.peoplecore.reportexport.infrastructure.storage.ExportArtifactStorage;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="peoplecore.exports.enabled",havingValue="true")
public class ReportExportConfig {
    @Bean(name="exportRevocableTransfer",destroyMethod="close")
    RevocableDocumentTransfer exportRevocableTransfer() {
        return new RevocableDocumentTransfer(2,Duration.ofSeconds(15),Duration.ofSeconds(2));
    }

    @Bean(name="exportS3Client",destroyMethod="close")
    @ConditionalOnProperty(prefix="peoplecore.exports.storage",name={"endpoint","region","bucket"})
    S3Client exportS3Client(@Value("${peoplecore.exports.storage.endpoint}") URI endpoint,
                          @Value("${peoplecore.exports.storage.region}") String region) {
        String host=endpoint.getHost();
        boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);
        if(host==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
            || !("https".equalsIgnoreCase(endpoint.getScheme()) || local && "http".equalsIgnoreCase(endpoint.getScheme())))
            throw new IllegalArgumentException("Export storage endpoint must use HTTPS or local loopback");
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(10)).retryStrategy(AwsRetryStrategy.doNotRetry()).build()).build();
    }

    @Bean
    @ConditionalOnProperty(prefix="peoplecore.exports.storage",name={"endpoint","region","bucket"})
    ExportArtifactStorage exportArtifactStorage(@Qualifier("exportS3Client") S3Client client,
                                               @Value("${peoplecore.exports.storage.bucket}") String bucket) {
        return new ExportArtifactStorage(client,bucket);
    }
}
