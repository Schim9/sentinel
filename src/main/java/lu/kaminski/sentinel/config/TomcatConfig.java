package lu.kaminski.sentinel.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    private final SentinelProperties props;

    public TomcatConfig(SentinelProperties props) {
        this.props = props;
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> additionalConnector() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(props.getPublicPort());
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
