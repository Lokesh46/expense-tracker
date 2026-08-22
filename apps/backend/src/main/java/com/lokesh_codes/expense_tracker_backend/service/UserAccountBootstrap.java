package com.lokesh_codes.expense_tracker_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.lokesh_codes.expense_tracker_backend.entity.Role;
import com.lokesh_codes.expense_tracker_backend.repository.UserRepository;

/**
 * Brings the {@code users} table into a state the rest of the application can
 * assume, once, at startup.
 *
 * <p>Two jobs, in this order, because the second reads rows the first repairs.
 *
 * <p><b>Legacy roles.</b> Before roles were an enum the column held the string
 * {@code "USER"}. Left alone, every such row would fail to map. {@link Role#parse}
 * tolerates it defensively, but tolerating a value forever is how it survives
 * forever, so the rows are rewritten here.
 *
 * <p><b>The first administrator.</b> Delegated to {@link AdminPromotion}, which is
 * also applied at registration. This path covers the account that already existed
 * before {@code ADMIN_USERNAME} was set.
 */
@Component
public class UserAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserAccountBootstrap.class);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AdminPromotion adminPromotion;

    public UserAccountBootstrap(JdbcTemplate jdbc,
            UserRepository users,
            AdminPromotion adminPromotion) {
        this.jdbc = jdbc;
        this.users = users;
        this.adminPromotion = adminPromotion;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        normaliseLegacyRoles();
        promoteConfiguredAdmin();
        warnIfNoAdministrator();
    }

    /**
     * Run through JDBC rather than JPA on purpose: loading the rows as entities is
     * exactly what fails when the stored value is not a member of the enum, so the
     * repair cannot go through the mapping it is repairing.
     */
    private void normaliseLegacyRoles() {
        int updated = jdbc.update(
                "update users set role = 'MEMBER' where role is null or role not in ('ADMIN', 'MEMBER')");
        if (updated > 0) {
            log.info("Normalised {} legacy user role value(s) to MEMBER", updated);
        }
    }

    private void promoteConfiguredAdmin() {
        if (!adminPromotion.isConfigured()) {
            return;
        }

        var found = users.findByUsername(adminPromotion.configuredUsername());
        if (found.isEmpty()) {
            // Not a problem, and not something to warn about: the usual order is to
            // set the variable and then register, and registration promotes on its
            // own without needing another restart.
            log.info("ADMIN_USERNAME is set to '{}'. No such account yet — it will be "
                    + "promoted as soon as it registers.", adminPromotion.configuredUsername());
            return;
        }

        adminPromotion.apply(found.get(), "at startup");
    }

    /**
     * A deployment with accounts but no administrator is a deployment nobody can
     * administer. Worth saying out loud, with the remedy, rather than leaving it
     * to be discovered from a 403.
     */
    private void warnIfNoAdministrator() {
        if (users.countByRole(Role.ADMIN) > 0 || users.count() == 0) {
            return;
        }
        log.warn("No administrator account exists. Set ADMIN_USERNAME to an existing username, "
                + "or to one about to register, to appoint one.");
    }
}
