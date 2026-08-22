package com.lokesh_codes.expense_tracker_backend.entity;

import java.time.LocalDate;

/** How often a {@link RecurringTransaction} produces a transaction. */
public enum Frequency {
    DAILY {
        @Override
        public LocalDate advance(LocalDate from) {
            return from.plusDays(1);
        }
    },
    WEEKLY {
        @Override
        public LocalDate advance(LocalDate from) {
            return from.plusWeeks(1);
        }
    },
    MONTHLY {
        @Override
        public LocalDate advance(LocalDate from) {
            return from.plusMonths(1);
        }
    },
    YEARLY {
        @Override
        public LocalDate advance(LocalDate from) {
            return from.plusYears(1);
        }
    };

    /**
     * The next due date after {@code from}.
     *
     * <p>{@code plusMonths} clamps to the end of shorter months, so a rule due
     * on the 31st fires on the 28th/30th where needed rather than being skipped.
     */
    public abstract LocalDate advance(LocalDate from);
}
