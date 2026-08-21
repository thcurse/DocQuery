package com.doc.docquery;

import com.doc.docquery.command.BootstrapAdminCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * DocQuery 单体服务入口。
 *
 * <p>当命令行包含管理员初始化参数时，应用在执行完一次性初始化命令后退出；
 * 其他情况下按普通 Web 服务启动。</p>
 */
@SpringBootApplication
@EnableScheduling
public class DocQueryApplication {

	public static void main(String[] args) {
		if (!BootstrapAdminCommand.isRequested(args)) {
			SpringApplication.run(DocQueryApplication.class, args);
			return;
		}

		SpringApplication application = new SpringApplication(DocQueryApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);

		try (ConfigurableApplicationContext context = application.run(args)) {
			int exitCode = SpringApplication.exit(context);
			if (exitCode != 0) {
				System.exit(exitCode);
			}
		}
	}

}
