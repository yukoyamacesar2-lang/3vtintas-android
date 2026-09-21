package br.com.tresvtintas.mobile.feature.quote;

import static org.junit.Assert.assertEquals;

import br.com.tresvtintas.mobile.core.customer.CustomerSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.Test;

public final class MaterialQuoteCustomerScopeTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    @Test
    public void selectedOrganizationExcludesUnassignedAndOtherStores() {
        CustomerSummary sameStore = customer(1, OptionalLong.of(10));
        CustomerSummary otherStore = customer(2, OptionalLong.of(20));
        CustomerSummary unassigned = customer(3, OptionalLong.empty());

        assertEquals(
                List.of(sameStore),
                MaterialQuoteCustomerScope.forOrganization(
                        List.of(sameStore, otherStore, unassigned),
                        OptionalLong.of(10)));
    }

    @Test
    public void missingOrganizationPreservesLegacyPickerScope() {
        CustomerSummary sameStore = customer(1, OptionalLong.of(10));
        CustomerSummary unassigned = customer(2, OptionalLong.empty());

        assertEquals(
                List.of(sameStore, unassigned),
                MaterialQuoteCustomerScope.forOrganization(
                        List.of(sameStore, unassigned),
                        OptionalLong.empty()));
    }

    private static CustomerSummary customer(long id, OptionalLong organizationId) {
        return new CustomerSummary(
                id,
                organizationId,
                "Cliente " + id,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                NOW,
                NOW);
    }
}
