package com.wuyunbin.rag.tool;

import com.wuyunbin.rag.dto.GeoResponse;
import com.wuyunbin.rag.dto.WeatherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 高德地图工具集：提供地理编码和实况天气查询能力。
 *
 * <p>当用户询问天气或地理位置时，AI 会自动调用这些工具：
 * <ol>
 *   <li>先调用 {@link #geocode} 获取区域编码（adcode）</li>
 *   <li>再调用 {@link #getWeather} 用 adcode 查询实况天气</li>
 * </ol>
 *
 * <p>API Key 从 application.properties 注入，不在代码中硬编码。</p>
 */
@Slf4j
@Component
public class AmapTools {

    private final RestTemplate restTemplate;
    private final String geoKey;
    private final String weatherKey;

    public AmapTools(
            RestTemplate restTemplate,
            @Value("${amap.geo.key:}") String geoKey,
            @Value("${amap.weather.key:}") String weatherKey) {
        this.restTemplate = restTemplate;
        this.geoKey = geoKey;
        this.weatherKey = weatherKey;
    }

    /**
     * 根据地名查询地理编码信息。
     * 返回区域编码（adcode）和格式化地址，供后续天气查询使用。
     *
     * @param address 地名，如"福州"、"厦门理工学院"
     * @return 包含 adcode 和格式化地址的文本
     */
    @Tool(description = "根据地名查询地理编码信息，返回区域编码(adcode)和格式化地址。当用户询问某个地点的天气时，先用此工具获取区域编码。参数address是地名，如\"福州\"、\"厦门理工学院\"")
    public String geocode(String address) {
        log.info("Tool geocode called: address={}", address);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", geoKey)
                    .queryParam("address", address)
                    .build()
                    .toUri();

            GeoResponse response = restTemplate.getForObject(uri, GeoResponse.class);
            if (response == null || response.getGeocodes() == null || response.getGeocodes().isEmpty()) {
                return "未找到\"" + address + "\"的地理编码信息";
            }

            GeoResponse.Geocode geo = response.getGeocodes().get(response.getGeocodes().size() - 1);
            return String.format("地名：%s\n区域编码(adcode)：%s\n格式化地址：%s\n经纬度：%s",
                    address, geo.getAdcode(), geo.getFormatted_address(), geo.getLocation());
        } catch (Exception e) {
            log.error("地理编码查询失败: address={}", address, e);
            return "地理编码查询失败：" + e.getMessage();
        }
    }

    /**
     * 根据城市区域编码查询实况天气。
     *
     * @param adcode 城市区域编码，如"350100"（福州）、"350200"（厦门）
     * @return 实况天气信息文本
     */
    @Tool(description = "根据城市区域编码(adcode)查询实况天气。参数adcode是6位区域编码，如350100表示福州。先用geocode工具获取adcode，再用此工具查天气")
    public String getWeather(String adcode) {
        log.info("Tool getWeather called: adcode={}", adcode);
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://restapi.amap.com/v3/weather/weatherInfo")
                    .queryParam("key", weatherKey)
                    .queryParam("city", adcode)
                    .build()
                    .toUri();

            WeatherResponse response = restTemplate.getForObject(uri, WeatherResponse.class);
            if (response == null || response.getLives() == null || response.getLives().isEmpty()) {
                return "未找到区域编码" + adcode + "的天气信息";
            }

            WeatherResponse.Live live = response.getLives().get(0);
            return String.format("城市：%s\n天气：%s\n温度：%s℃\n风向：%s\n风力：%s\n湿度：%s\n发布时间：%s",
                    live.getCity(), live.getWeather(), live.getTemperature(),
                    live.getWinddirection(), live.getWindpower(), live.getHumidity(),
                    live.getReporttime());
        } catch (Exception e) {
            log.error("天气查询失败: adcode={}", adcode, e);
            return "天气查询失败：" + e.getMessage();
        }
    }
}
