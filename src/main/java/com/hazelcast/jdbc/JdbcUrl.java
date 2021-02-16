/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.jdbc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdbcUrl {

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("^jdbc:hazelcast://"
            + "(?<authority>\\S+?(?=[/]))"
            + "/(?<schema>\\S+?)"
            + "(\\?(?<parameters>\\S*))?$");
    private static final Pattern KEY_VALUE_PROPERTY = Pattern.compile("(?<property>\\S+)\\[(?<key>\\S+)]");

    private final List<String> authorities;
    private final String schema;
    private final String rawUrl;
    private final Map<String, ParameterValue> properties = new HashMap<>();
    private String rawAuthority;

    private JdbcUrl(List<String> authorities, String schema, String rawUrl) {
        this.authorities = authorities;
        this.schema = schema;
        this.rawUrl = rawUrl;
    }

    public List<String> getAuthorities() {
        return authorities;
    }

    public String getSchema() {
        return schema;
    }


    public String getProperty(String key) {
        ParameterValue parameterValue = properties.get(key);
        if (parameterValue == null) {
            return null;
        }
        return parameterValue.asPropertyValue();
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public String getRawAuthority() {
        return rawAuthority;
    }

    private void parseProperties(String parametersString) {
        if (parametersString == null) {
            return;
        }
        for (String parameter : parametersString.split("&")) {
            String[] paramAndValue = parameter.split("=");
            if (paramAndValue.length != 2) {
                continue;
            }
            String k = paramAndValue[0];
            String v = paramAndValue[1];
            Map.Entry<String, ParameterValue> parameterValue = parameterValue(k, v);
            properties.compute(parameterValue.getKey(), (key, value) -> {
                ParameterValue param = parameterValue.getValue();
                if (value == null) {
                    return param;
                } else {
                    param.getKeyValues().forEach(value::setValue);
                    return value;
                }
            });
        }
    }

    static boolean acceptsUrl(String url) {
        return JDBC_URL_PATTERN.matcher(url).matches();
    }

    static JdbcUrl valueOf(String url, Properties info) {
        Matcher matcher = JDBC_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return null;
        }

        String rawAuthority = decodeUrl(matcher.group("authority"));
        JdbcUrl jdbcUrl = new JdbcUrl(Arrays.asList(rawAuthority.split(",")), matcher.group("schema"), url);
        jdbcUrl.rawAuthority = rawAuthority;
        if (info != null) {
            info.forEach((k, v) -> jdbcUrl.properties.put(k.toString(), new SimpleValue(v.toString())));
        }
        jdbcUrl.parseProperties(matcher.group("parameters"));
        return jdbcUrl;
    }

    static JdbcUrl valueOf(String url) {
        return valueOf(url, new Properties());
    }

    private static String decodeUrl(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static Map.Entry<String, ParameterValue> parameterValue(String key, String value) {
        Matcher keyValuePropertyMatcher = KEY_VALUE_PROPERTY.matcher(key);
        if (keyValuePropertyMatcher.matches()) {
            String property = keyValuePropertyMatcher.group("property");
            KeyValuePairs keyValuePairs = new KeyValuePairs();
            keyValuePairs.setValue(keyValuePropertyMatcher.group("key"), value);
            return new AbstractMap.SimpleEntry<>(property, keyValuePairs);
        }
        return new AbstractMap.SimpleEntry<>(key, new SimpleValue(value));
    }

    private interface ParameterValue {
        Map<String, String> getKeyValues();
        void setValue(String key, String value);
        String asPropertyValue();
    }

    private static final class SimpleValue implements ParameterValue {

        private String value;

        private SimpleValue(String value) {
            this.value = value;
        }

        @Override
        public Map<String, String> getKeyValues() {
            return Collections.singletonMap(null, value);
        }

        @Override
        public void setValue(String key, String value) {
            this.value = value;
        }

        @Override
        public String asPropertyValue() {
            return value;
        }
    }

    private static final class KeyValuePairs implements ParameterValue {

        private final Map<String, String> keyValues = new HashMap<>();

        @Override
        public Map<String, String> getKeyValues() {
            return keyValues;
        }

        @Override
        public void setValue(String key, String value) {
            keyValues.put(key, value);
        }

        @Override
        public String asPropertyValue() {
            StringBuilder result = new StringBuilder();
            String prefix = "";
            for (Map.Entry<String, String> entries : keyValues.entrySet()) {
                result.append(prefix);
                prefix = ",";
                result.append(entries.getKey()).append("=").append(entries.getValue());
            }
            return result.toString();
        }
    }
}
