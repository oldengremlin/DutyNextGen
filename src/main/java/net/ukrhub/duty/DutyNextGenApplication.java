package net.ukrhub.duty;

import net.ukrhub.duty.auth.UserAdminCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DutyNextGenApplication {

    public static void main(String[] args) {
        // CLI-режим (без веб-сервера й Spring-контексту) — первинна
        // ініціалізація/зміна пароля: java -jar duty-nextgen.jar add-user <ім'я>
        if (args.length > 0 && "add-user".equals(args[0])) {
            UserAdminCli.addUser(args);
            return;
        }
        SpringApplication.run(DutyNextGenApplication.class, args);
    }
}
