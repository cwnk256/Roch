package com.wuyunbin.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 高德地理编码 API（/v3/geocode/geo）响应。
 *
 * <p>字段名与高德返回的 JSON key 保持一致（snake_case），
 * 故 formatted_address 的 getter 为 getFormatted_address()。</p>
 *
 * <p>高德 API 对部分字段（city/district/street 等）会根据地址级别返回
 * 字符串或空数组 []，故统一用 Object 类型容忍 Jackson 反序列化。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeoResponse {

    /**
     * 状态码：1=成功，0=失败
     */
    private String status;

    /**
     * 状态说明
     */
    private String info;

    /**
     * 状态码（10000=正确）
     */
    private String infocode;

    /**
     * 地理编码结果列表
     */
    private List<Geocode> geocodes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Geocode {
        /**
         * 结构化地址信息，如：福建省厦门市集美区厦门理工学院
         */
        private String formatted_address;

        /**
         * 国家
         */
        private Object country;

        /**
         * 省份
         */
        private Object province;

        /**
         * 城市（可能返回字符串或空数组 []）
         */
        private Object city;

        /**
         * 区县（可能返回字符串或空数组 []）
         */
        private Object district;

        /**
         * 区域编码，如：350211
         */
        private String adcode;

        /**
         * 坐标点：经度,纬度
         */
        private String location;

        /**
         * 匹配级别
         */
        private String level;
    }
}
