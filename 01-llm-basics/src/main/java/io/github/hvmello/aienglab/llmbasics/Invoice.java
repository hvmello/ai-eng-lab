package io.github.hvmello.aienglab.llmbasics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The typed result of an extraction. Its components must match {@code invoice.schema.json}; a test enforces it so
 * the two cannot drift apart.
 */
public record Invoice(String vendor, String currency, LocalDate issueDate, List<LineItem> lineItems, BigDecimal total) {

    public record LineItem(String description, Integer quantity, BigDecimal unitPrice) {

        /** Amount of this line: quantity times unit price. */
        public BigDecimal amount() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
