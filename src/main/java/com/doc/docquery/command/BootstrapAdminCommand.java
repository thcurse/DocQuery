package com.doc.docquery.command;

import com.doc.docquery.service.AdminBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;
import java.util.List;

/**
 * 首个平台管理员的一次性命令行初始化入口。
 *
 * <p>只有显式传入初始化参数时才执行；参数必须唯一且完整。命令结束后清除
 * 内存中的明文密码，并通过退出码向调用方报告结果。</p>
 */
@Component
public class BootstrapAdminCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final String COMMAND_NAME = "bootstrap-admin";
    private static final String LOGIN_NAME_OPTION = "login-name";

    private final AdminBootstrapService adminBootstrapService;
    private int exitCode;

    public BootstrapAdminCommand(AdminBootstrapService adminBootstrapService) {
        this.adminBootstrapService = adminBootstrapService;
    }

    /** 判断本次启动是否请求执行管理员初始化命令。 */
    public static boolean isRequested(String[] args) {
        return Arrays.asList(args).contains(COMMAND_NAME);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND_NAME)) {
            return;
        }

        try {
            String loginName = requiredSingleOption(args, LOGIN_NAME_OPTION);
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException("An interactive terminal is required");
            }

            char[] password = console.readPassword("Initial administrator password: ");
            char[] confirmation = console.readPassword("Confirm password: ");
            try {
                if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                    throw new IllegalArgumentException("Password confirmation does not match");
                }

                String normalizedLoginName = adminBootstrapService.createInitialPlatformAdmin(
                        loginName,
                        password
                );
                System.out.println("Initial platform administrator created: " + normalizedLoginName);
            } finally {
                clear(password);
                clear(confirmation);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            exitCode = 1;
            System.err.println("Cannot create initial platform administrator: " + exception.getMessage());
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** 读取必须且只能出现一次的命令行选项，避免静默采用歧义参数。 */
    private String requiredSingleOption(ApplicationArguments args, String optionName) {
        List<String> values = args.getOptionValues(optionName);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Required option: --" + optionName + "=<value>");
        }
        return values.get(0);
    }

    /** 尽早擦除可变字符数组中的密码副本。 */
    private void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
