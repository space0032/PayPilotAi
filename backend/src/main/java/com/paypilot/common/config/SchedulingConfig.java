package com.paypilot.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the payment reconciliation sweeps (@Scheduled). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
