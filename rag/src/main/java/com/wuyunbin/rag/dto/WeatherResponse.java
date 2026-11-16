package com.wuyunbin.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 高德天气查询 API（/v3/weather/weatherInfo）实况天气响应。
 *
 * <p>字段名与高德返回的 JSON key 保持一致（winddirection/windpower 等均为全小写单词）。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherResponse {

    /**
     * 状态码：1=成功，0=失败
     */
    private String status;

    /**
     * 返回结果总数
     */
    private String count;

    /**
     * 状态说明
     */
    private String info;

    /**
     * 状态码（10000=正确）
     */
    private String infocode;

    /**
     * 实况天气列表
     */
    private List<Live> lives;

    @Data
    public static class Live {
        /**
         * 省份
         */
        private String province;

        /**
         * 城市
         */
        private String city;

        /**
         * 区域编码
         */
        private String adcode;

        /**
         * 天气现象，如：晴、多云、雨
         */
        private String weather;

        /**
         * 实时气温（摄氏度）
         */
        private String temperature;

        /**
         * 风向，如：东南
         */
        private String winddirection;

        /**
         * 风力，如：≤3
         */
        private String windpower;

        /**
         * 空气湿度（百分比）
         */
        private String humidity;

        /**
         * 数据发布时间
         */
        private String reporttime;
    }
}
