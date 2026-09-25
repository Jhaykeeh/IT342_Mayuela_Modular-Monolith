package edu.cit.mayuela.supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the LegacySupply adapter configuration properties. */
@Configuration
@EnableConfigurationProperties(LegacySupplyProperties.class)
class SupplierConfig {
}