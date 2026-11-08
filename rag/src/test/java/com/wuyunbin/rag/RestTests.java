package com.wuyunbin.rag;

import com.wuyunbin.rag.dto.GeoResponse;
import com.wuyunbin.rag.dto.WeatherResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Slf4j
@SpringBootTest
public class RestTests {
    @Resource
    private RestTemplate restTemplate;

    @Test
    public void test01() {
        URI uri = UriComponentsBuilder
                .fromUriString("https://restapi.amap.com/v3/geocode/geo")
                .queryParam("key", System.getenv("AMAP_GEO_KEY"))
                .queryParam("address", "厦门理工学院")
                .build()
                .toUri();


        ResponseEntity<GeoResponse> response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                null,
                GeoResponse.class
        );

        log.info("{}", response);
        log.info("{}", response.getBody());
        log.info("{}", response.getBody().getGeocodes().getLast().getAdcode());
        log.info("{}", response.getBody().getGeocodes().getLast().getFormatted_address());

    }


    @Test
    public void test02() {
        URI uri = UriComponentsBuilder
                .fromUriString("https://restapi.amap.com/v3/weather/weatherInfo")
                .queryParam("key", System.getenv("AMAP_WEATHER_KEY"))
                .queryParam("city", "350211")
                .build()
                .toUri();


        ResponseEntity<WeatherResponse> response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                null,
                WeatherResponse.class
        );

        log.info("{}", response);
        log.info("{}", response.getBody());
        WeatherResponse.Live first = response.getBody().getLives().getFirst();
        log.info("{}天气{},风向{},风力{},温度{}", first.getCity(), first.getWeather(), first.getWinddirection(), first.getWindpower(), first.getTemperature());

    }
}
