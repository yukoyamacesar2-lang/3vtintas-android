package br.com.tresvtintas.mobile.feature.quote;

import br.com.tresvtintas.mobile.core.customer.CustomerSummary;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * Keeps the customer picker aligned with the store selected for the quote.
 *
 * The server remains the final authorization boundary; this client-side scope
 * prevents an administrator from accidentally selecting an unassigned or
 * another-store customer and only discovering it after submitting the quote.
 */
final class MaterialQuoteCustomerScope {
    private MaterialQuoteCustomerScope() {
    }

    static List<CustomerSummary> forOrganization(
            List<CustomerSummary> customers,
            OptionalLong organizationId) {
        if (organizationId == null || organizationId.isEmpty()) {
            return List.copyOf(customers);
        }
        long selectedOrganizationId = organizationId.getAsLong();
        return Collections.unmodifiableList(customers.stream()
                .filter(customer -> customer.organizationId().isPresent())
                .filter(customer -> customer.organizationId().getAsLong()
                        == selectedOrganizationId)
                .collect(Collectors.toList()));
    }
}
