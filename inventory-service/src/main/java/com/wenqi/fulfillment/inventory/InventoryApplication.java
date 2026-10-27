package com.wenqi.fulfillment.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.wenqi.fulfillment")
public class InventoryApplication {
  public static void main(String[] args) {
    SpringApplication.run(InventoryApplication.class, args);
  }
}
