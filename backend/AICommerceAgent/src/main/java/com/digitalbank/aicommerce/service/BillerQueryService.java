package com.digitalbank.aicommerce.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.digitalbank.aicommerce.client.BillerFeignClient;
import com.digitalbank.aicommerce.client.dto.BillerPage;
import com.digitalbank.aicommerce.client.dto.BillerView;
import com.digitalbank.aicommerce.domain.ActionOutcome;
import com.digitalbank.aicommerce.domain.AgentActionLog;

import lombok.RequiredArgsConstructor;

/**
 * Reads the billers of the calling customer through Biller Service.
 *
 * <p>Biller Service scopes its list to the customer on the relayed token, so the
 * agent sees the caller's own billers and no others.</p>
 */
@Service
@RequiredArgsConstructor
public class BillerQueryService {

    private static final String TOOL_NAME = "get_my_billers";

    /** Upper bound on the billers fetched for one turn. */
    private static final int PAGE_LIMIT = 100;

    private final BillerFeignClient billerClient;
    private final CallerIdentity callerIdentity;
    private final AuditService auditService;

    public List<BillerView> myBillers(String conversationId) {

        String customerId;

        try {
            customerId = callerIdentity.requireCustomerId();
        } catch (RuntimeException denied) {
            auditService.record(
                    AgentActionLog.starting(TOOL_NAME, null, null, conversationId),
                    ActionOutcome.DENIED,
                    denied.getMessage(),
                    null);
            throw denied;
        }

        AgentActionLog entry = AgentActionLog.starting(
                TOOL_NAME, customerId, callerIdentity.subject(), conversationId);

        try {
            BillerPage page = billerClient.listBillers(PAGE_LIMIT, 0);
            List<BillerView> billers = page == null ? List.of() : page.itemsOrEmpty();

            auditService.record(entry, ActionOutcome.SUCCESS,
                    "returned " + billers.size() + " biller(s)", 200);

            return billers;

        } catch (RuntimeException ex) {
            auditService.record(entry, ActionOutcome.ERROR, ex.getMessage(), null);
            throw ex;
        }
    }

    /**
     * Finds one of the caller's billers by reference number.
     *
     * <p>Matching is exact. A reference number the model produced that does not
     * appear in the caller's own list resolves to empty, and the proposal is
     * refused rather than attempted.</p>
     */
    public Optional<BillerView> findOwnedByReference(String referenceNumber, String conversationId) {

        if (referenceNumber == null || referenceNumber.isBlank()) {
            return Optional.empty();
        }

        return myBillers(conversationId).stream()
                .filter(biller -> referenceNumber.equals(biller.referenceNumber()))
                .findFirst();
    }

    /** Registry check that the biller is currently payable. */
    public boolean isActiveInRegistry(String referenceNumber) {
        return Boolean.TRUE.equals(billerClient.isActive(referenceNumber));
    }
}
