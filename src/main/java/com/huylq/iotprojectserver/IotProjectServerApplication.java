package com.huylq.iotprojectserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.huylq.iotprojectserver")
@EnableScheduling
public class IotProjectServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(IotProjectServerApplication.class, args);
  }
}
