package mn.netgroup.cb.productcatalog.config;

import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the committed contract says about this service, in the form the generator reads.
 *
 * <p>lld D-05, and this is a correction refutation found rather than a preference: Actuator's
 * default produced-types list carries {@code application/vnd.spring-boot.actuator.v3+json}
 * <b>first</b>, so a client sending {@code Accept} of {@code *}{@code /}{@code *} receives the vendor type — and a
 * hand-written {@code application/json} contract for the health operation would simply have been
 * false. The bean below makes it true by making {@code application/json} the only produced type
 * an Actuator endpoint offers.
 *
 * <p>Springdoc's own actuator rendering stays off ({@code springdoc.show-actuator=false}): it
 * mangles the {@code operationId} for uniqueness and emits vendor media types, both of which move
 * under a springdoc bump and would flap the drift gate.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public EndpointMediaTypes endpointMediaTypes() {
        return new EndpointMediaTypes(
                java.util.List.of("application/json"), java.util.List.of("application/json"));
    }
}
