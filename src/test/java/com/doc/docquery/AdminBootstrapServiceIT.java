package com.doc.docquery;

import com.doc.docquery.service.AdminBootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.CharBuffer;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdminBootstrapServiceIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Autowired
    private AdminBootstrapService adminBootstrapService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsOnlyOnePlatformAdminWithoutCreatingTenant() {
        char[] password = "Correct Horse Battery 2026!".toCharArray();

        assertThat(count("admin_user")).isZero();
        assertThat(count("tenant")).isZero();

        assertThatThrownBy(() -> adminBootstrapService.createInitialPlatformAdmin(
                "platform.admin",
                "x".repeat(73).toCharArray()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Password must contain at least 12 characters and at most 72 UTF-8 bytes");
        assertThat(count("admin_user")).isZero();

        String loginName = adminBootstrapService.createInitialPlatformAdmin(
                "  Platform.Admin  ",
                password
        );

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                """
                SELECT tenant_id, login_name, password_hash, role, status
                FROM admin_user
                """
        );

        assertThat(loginName).isEqualTo("platform.admin");
        assertThat(saved.get("tenant_id")).isNull();
        assertThat(saved.get("login_name")).isEqualTo("platform.admin");
        assertThat(saved.get("role")).isEqualTo("1");
        assertThat(saved.get("status")).isEqualTo("1");

        String passwordHash = (String) saved.get("password_hash");
        assertThat(passwordHash)
                .startsWith("{bcrypt}")
                .doesNotContain("Correct Horse Battery 2026!");
        assertThat(passwordEncoder.matches(CharBuffer.wrap(password), passwordHash)).isTrue();

        assertThatThrownBy(() -> adminBootstrapService.createInitialPlatformAdmin(
                "another-admin",
                "Another Strong Password 2026!".toCharArray()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("DocQuery already has an administrator");

        assertThat(count("admin_user")).isEqualTo(1);
        assertThat(count("tenant")).isZero();
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }
}
