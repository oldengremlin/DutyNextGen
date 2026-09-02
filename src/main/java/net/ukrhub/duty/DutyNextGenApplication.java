package net.ukrhub.duty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DutyNextGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(DutyNextGenApplication.class, args);
    }
}
