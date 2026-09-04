/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrhub.duty;

import net.ukrhub.duty.auth.UserAdminCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
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
