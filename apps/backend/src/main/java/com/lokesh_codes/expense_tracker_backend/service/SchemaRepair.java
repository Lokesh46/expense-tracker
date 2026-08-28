package com.lokesh_codes.expense_tracker_backend.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Frees enum columns from the set of values they were created with.
 *
 * <p>An {@code @Enumerated(STRING)} column is not stored as plain text by
 * default. Hibernate pins the permitted values into the schema — as H2's native
 * {@code ENUM} type, or as a {@code check (x in (...))} constraint on
 * PostgreSQL — listing what the enum contained on the day the table was made. It
 * never revisits that. So adding a value to the enum works on a database created
 * afterwards and fails on every database created before, which is every database
 * that already has data in it.
 *
 * <p>This is not hypothetical, and it is the reason this class exists. Adding
 * {@code TRANSACTIONS_IMPORTED} to {@code ActivityAction} made every CSV import
 * fail against the real database while all 177 tests passed. Tests build the
 * schema from scratch with {@code create-drop}, so the pinned list is always
 * regenerated with the current values — the failure is invisible to them by
 * construction, and only appears on an upgrade.
 *
 * <p>New databases avoid the problem entirely, because every
 * {@code @Enumerated(STRING)} field now carries an explicit varchar
 * {@code columnDefinition}. The global
 * {@code hibernate.type.preferred_enum_jdbc_type} setting was tried first and
 * did nothing: the columns were rewritten as ENUM on every boot and converted
 * back here on the next, a loop that was visible only because this class logs
 * what it changes. This class is for the databases that already exist.
 *
 * <p>Nothing is validated away by removing the pin. The enum is still the
 * definition of what is allowed, and the column is still written only by this
 * application; a copy of the value list in the schema was never a second
 * opinion, only a second thing to keep in step.
 *
 * <p>Runs before anything that writes, and never fails a startup: a database
 * that needs no repair is the normal case, not an error.
 */
@Component
@Order(0)
public class SchemaRepair implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaRepair.class);

    /** Comfortably longer than the longest enum constant, with room to grow. */
    private static final int COLUMN_LENGTH = 64;

    /**
     * Every column mapped from an enum.
     *
     * <p>All of them, not only the ones that have grown: the cost of listing a
     * column that never changes is one metadata query per startup, and the cost
     * of omitting one is an outage the tests cannot see.
     */
    private static final List<String[]> ENUM_COLUMNS = List.of(
            new String[] { "activity_log", "action" },
            new String[] { "transactions", "type" },
            new String[] { "category_rules", "match_type" },
            new String[] { "recurring_transactions", "frequency" },
            new String[] { "users", "role" },
            new String[] { "users", "status" });

    private final JdbcTemplate jdbc;

    public SchemaRepair(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String[] target : ENUM_COLUMNS) {
            String table = target[0];
            String column = target[1];

            // Order matters on PostgreSQL: a check constraint referencing the
            // column can prevent the type from being altered.
            dropCheckConstraints(table, column);
            convertToText(table, column);
        }
    }

    /**
     * Rewrites a column still declared as a database-level enum into plain text.
     *
     * <p>Skipped when the column is already a character type, which is the case
     * on any database created since the Hibernate setting was added — so on a
     * healthy instance this does nothing but read one row of metadata.
     */
    private void convertToText(String table, String column) {
        String type = declaredType(table, column);
        if (type == null || type.toUpperCase(java.util.Locale.ROOT).startsWith("CHARACTER")
                || type.toUpperCase(java.util.Locale.ROOT).contains("VARCHAR")) {
            return;
        }

        try {
            // SQL-standard form, accepted by both H2 and PostgreSQL. Every value
            // already in the column is one of the enum's names, so the conversion
            // to text is lossless.
            jdbc.execute("alter table " + table + " alter column " + column
                    + " set data type varchar(" + COLUMN_LENGTH + ")");
            log.info("Converted {}.{} from {} to varchar so its values are no longer pinned "
                    + "to what the enum contained when the table was created", table, column, type);
        } catch (DataAccessException e) {
            log.warn("Could not convert {}.{} from {} to varchar: {}. Adding a new value to this "
                    + "enum will fail until it is converted by hand.", table, column, type,
                    e.getMessage());
        }
    }

    private String declaredType(String table, String column) {
        try {
            List<String> types = jdbc.queryForList("""
                    select data_type from information_schema.columns
                    where lower(table_name) = ? and lower(column_name) = ?
                    """, String.class, table, column);
            return types.isEmpty() ? null : types.get(0);
        } catch (DataAccessException e) {
            // The table may not exist yet on a first run, and a database that
            // does not expose these views is not a reason to refuse to start.
            log.debug("Could not read the type of {}.{}: {}", table, column, e.getMessage());
            return null;
        }
    }

    private void dropCheckConstraints(String table, String column) {
        List<String> names;
        try {
            names = jdbc.queryForList("""
                    select tc.constraint_name
                    from information_schema.table_constraints tc
                    join information_schema.constraint_column_usage ccu
                      on tc.constraint_name = ccu.constraint_name
                     and tc.constraint_schema = ccu.constraint_schema
                    where tc.constraint_type = 'CHECK'
                      and lower(tc.table_name) = ?
                      and lower(ccu.column_name) = ?
                    """, String.class, table, column);
        } catch (DataAccessException e) {
            log.debug("Could not inspect check constraints on {}.{}: {}", table, column,
                    e.getMessage());
            return;
        }

        for (String name : names) {
            try {
                // The name is generated by the database, not supplied by anyone,
                // and is quoted because generated names are usually uppercase.
                jdbc.execute("alter table " + table + " drop constraint \"" + name + "\"");
                log.info("Dropped stale check constraint {} on {}.{}", name, table, column);
            } catch (DataAccessException e) {
                log.warn("Could not drop check constraint {} on {}.{}: {}", name, table, column,
                        e.getMessage());
            }
        }
    }
}
