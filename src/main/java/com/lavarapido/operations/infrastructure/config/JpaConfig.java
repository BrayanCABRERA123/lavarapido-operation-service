package com.lavarapido.operations.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Los repositorios estan agrupados como interfaces anidadas (OperationsJpaRepositories);
 * Spring Data solo las encuentra con considerNestedRepositories.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.lavarapido.operations.infrastructure.adapter.out.persistence.repository",
        considerNestedRepositories = true)
class JpaConfig {
}
