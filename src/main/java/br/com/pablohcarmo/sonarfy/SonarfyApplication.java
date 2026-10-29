package br.com.pablohcarmo.sonarfy;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import java.util.TimeZone;

@SpringBootApplication
@EnableFeignClients
public class SonarfyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SonarfyApplication.class, args);
    }

    // Configura o fuso horário da aplicação (JVM) para UTC
    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
