package com.wuyunbin.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestConfig {

    /**
     * Spring Boot 4 的 webmvc starter 不默认引入 Jackson 自动配置，
     * 显式声明统一 ObjectMapper：注册 JSR-310 时间模块，LocalDateTime 以 ISO 字符串输出。
     * 该 Bean 同时供 RestTemplate 之外的业务代码（如 CurlService 落盘）注入使用。
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 设置连接超时时间（毫秒）
        factory.setConnectTimeout(5000);
        // 设置读取超时时间（毫秒）
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
}
