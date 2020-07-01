package test.client;

import org.r.framework.thrift.springboot.starter.annotation.EnableThriftClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * date 20-4-30 下午4:12
 *
 * @author casper
 **/
@SpringBootApplication
@EnableThriftClient
@EnableEurekaClient
public class TestClientApplication {


    public static void main(String[] args) {
        ConfigurableApplicationContext run = SpringApplication.run(TestClientApplication.class);
    }


}
