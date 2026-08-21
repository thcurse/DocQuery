package com.doc.docquery.service.impl;

import com.doc.docquery.entity.AdminUserEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.mapper.AdminUserMapper;
import com.doc.docquery.service.AdminBootstrapService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 初始平台管理员创建实现。
 *
 * <p>先检查系统必须为空，再依赖数据库唯一约束兜住并发初始化；密码仅以
 * 编码后的哈希进入持久层。</p>
 */
@Service
public class AdminBootstrapServiceImpl implements AdminBootstrapService {

    private static final Pattern LOGIN_NAME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{2,63}"
    );
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final String PLATFORM_ADMIN = AdminRole.PLATFORM_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapServiceImpl(
            AdminUserMapper adminUserMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public String createInitialPlatformAdmin(String requestedLoginName, char[] password) {
        String loginName = normalizeLoginName(requestedLoginName);
        validatePassword(password);

        if (adminUserMapper.countAll() != 0) {
            throw new IllegalStateException("DocQuery already has an administrator");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AdminUserEntity adminUser = new AdminUserEntity(
                null,
                null,
                loginName,
                passwordEncoder.encode(CharBuffer.wrap(password)),
                PLATFORM_ADMIN,
                ACTIVE,
                now,
                now
        );
        if (adminUserMapper.insert(adminUser) != 1) {
            throw new IllegalStateException("Initial platform administrator was not created");
        }
        return loginName;
    }

    private String normalizeLoginName(String requestedLoginName) {
        String loginName = Objects.requireNonNull(
                requestedLoginName,
                "Login name is required"
        ).strip().toLowerCase(Locale.ROOT);
        if (!LOGIN_NAME_PATTERN.matcher(loginName).matches()) {
            throw new IllegalArgumentException(
                    "Login name must match [a-z0-9][a-z0-9._-]{2,63}"
            );
        }
        return loginName;
    }

    private void validatePassword(char[] password) {
        Objects.requireNonNull(password, "Password is required");
        int utf8Bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining();
        if (password.length < MIN_PASSWORD_LENGTH || utf8Bytes > MAX_PASSWORD_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "Password must contain at least 12 characters and at most 72 UTF-8 bytes"
            );
        }
    }
}
