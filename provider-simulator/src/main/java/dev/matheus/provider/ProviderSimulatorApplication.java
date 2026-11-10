package dev.matheus.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProviderSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderSimulatorApplication.class, args);
    }
}
