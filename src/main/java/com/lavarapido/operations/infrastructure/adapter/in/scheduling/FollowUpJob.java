package com.lavarapido.operations.infrastructure.adapter.in.scheduling;

import com.lavarapido.operations.domain.port.in.FollowUpUseCase;
import com.lavarapido.operations.domain.port.in.FollowUpUseCase.FollowUpRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada pocos minutos revisa las reservas que terminaron y nadie cerró (FollowUpService.followUp):
 * cierra las que nunca tuvieron operario y avisa las que quedan por cerrar. Necesita INTERNAL_API_KEY
 * (lee y cierra reservas sin un usuario detrás). Se apaga con app.follow-up.enabled=false.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.follow-up.enabled", havingValue = "true", matchIfMissing = true)
class FollowUpJob {

    private static final Logger log = LoggerFactory.getLogger(FollowUpJob.class);

    private final FollowUpUseCase followUp;

    FollowUpJob(FollowUpUseCase followUp) {
        this.followUp = followUp;
    }

    @Scheduled(initialDelayString = "${app.follow-up.initial-delay:PT1M}",
            fixedDelayString = "${app.follow-up.interval:PT5M}")
    void run() {
        try {
            FollowUpRun run = followUp.followUp();
            if (run.closedAsNotAttended() > 0 || run.noticesSent() > 0) {
                log.info("Follow-up: {} booking(s) closed as not attended, {} notice(s) sent",
                        run.closedAsNotAttended(), run.noticesSent());
            }
        } catch (RuntimeException ex) {
            // booking caído o sin llave interna: se intenta en la siguiente vuelta
            log.warn("Could not follow up finished bookings: {}", ex.getMessage());
        }
    }
}
