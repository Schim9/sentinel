package lu.kaminski.sentinel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    private final SentinelProperties props;

    public ClockConfig(SentinelProperties props) {
        this.props = props;
    }

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of(props.getTimezone()));
    }
}
