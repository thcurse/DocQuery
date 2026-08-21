package com.doc.docquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * 密码编码基础配置。
 *
 * <p>使用可升级的 DelegatingPasswordEncoder，数据库中保存带算法标识的哈希，
 * 不保存或记录明文密码。</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${docquery.security.password.bcrypt-strength}") int bcryptStrength
    ) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(bcryptStrength);
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
    }
}
