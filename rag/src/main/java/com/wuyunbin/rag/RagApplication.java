package com.wuyunbin.rag;

import com.wuyunbin.rag.desktop.DesktopConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(RagApplication.class);
        application.addInitializers(DesktopConfiguration.initializer());
        application.run(args);
    }

}
