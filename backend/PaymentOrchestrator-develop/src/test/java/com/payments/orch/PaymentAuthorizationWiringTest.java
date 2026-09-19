package com.payments.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import com.payments.orch.client.AccountClient;
import com.payments.orch.client.AccountM2MClient;
import com.payments.orch.controller.PaymentController;
import com.payments.orch.security.FeignM2MOAuth2Config;
import com.payments.orch.service.StatusConsumer;

/**
 * The customer-initiated payment path must call Account Service with the
 * customer's own token, never with the service's.
 *
 * <p>Account Service lets a client-credentials token through its ownership
 * check: that bypass is what allows this service to settle payments
 * asynchronously (AccountServiceOwnershipTest pins it). The price is that the
 * choice of token decides whether the ownership check runs. When a customer
 * pays a bill, the hold on the debtor account is authorised only because the
 * customer's token is relayed; Account Service then refuses an account the
 * customer does not own. Were that call made with the M2M token, any
 * authenticated customer could pay from any account.</p>
 *
 * <p>The user token is relayed by FeignTokenRelayConfig, a global
 * configuration that applies to every Feign client here, including clients that
 * declare no configuration of their own. The one way to lose that protection
 * on this path is therefore to reach a client configured with
 * FeignM2MOAuth2Config, which adds the service token. That is what these tests
 * forbid, by walking everything reachable from the payment controller rather
 * than naming today's clients, so a new M2M client is caught too.</p>
 */
class PaymentAuthorizationWiringTest {

    private static final String OWN_PACKAGE = "com.payments";

    @Test
    @DisplayName("no Feign client reachable from the payment controller uses the M2M token")
    void userPathHasNoServiceTokenClient() {
        Set<Class<?>> clients = feignClientsReachableFrom(PaymentController.class);

        assertThat(clients)
                .as("the walk found no Feign clients, so this test would pass vacuously")
                .isNotEmpty();

        assertThat(clients)
                .filteredOn(client -> usesM2mToken(client))
                .as("a client on the customer-initiated payment path sends the service token, "
                        + "which bypasses Account Service's ownership check")
                .isEmpty();
    }

    @Test
    @DisplayName("the payment path reaches Account Service through the relay client")
    void userPathUsesRelayClientForAccountService() {
        // Proves the path actually reaches Account Service, so the absence of
        // an M2M client above is meaningful rather than an empty graph.
        assertThat(feignClientsReachableFrom(PaymentController.class))
                .contains(AccountClient.class)
                .doesNotContain(AccountM2MClient.class);

        assertThat(usesM2mToken(AccountClient.class)).isFalse();
    }

    @Test
    @DisplayName("the M2M client is detected where it is meant to be: the settlement consumer")
    void walkDetectsM2mClient() {
        // Guards the guard. StatusConsumer settles payments from Kafka with no
        // customer present, so it legitimately uses the service token. If the
        // walk or the configuration check stopped working, this would fail
        // instead of the tests above silently passing.
        Set<Class<?>> clients = feignClientsReachableFrom(StatusConsumer.class);

        assertThat(clients).contains(AccountM2MClient.class);
        assertThat(usesM2mToken(AccountM2MClient.class)).isTrue();
    }

    // ------------------------------------------------------------ helpers

    private static boolean usesM2mToken(Class<?> client) {
        FeignClient feign = client.getAnnotation(FeignClient.class);
        return feign != null
                && Arrays.asList(feign.configuration()).contains(FeignM2MOAuth2Config.class);
    }

    /**
     * Breadth-first walk of declared field types from {@code root}, staying in
     * this service's own packages, collecting every interface annotated
     * {@code @FeignClient}.
     */
    private static Set<Class<?>> feignClientsReachableFrom(Class<?> root) {
        Set<Class<?>> clients = new LinkedHashSet<>();
        Set<Class<?>> seen = new HashSet<>(List.of(root));
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(root));

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            List<Field> fields = new ArrayList<>(Arrays.asList(current.getDeclaredFields()));

            for (Field field : fields) {
                Class<?> type = field.getType();
                if (!type.getName().startsWith(OWN_PACKAGE) || !seen.add(type)) {
                    continue;
                }
                if (type.isAnnotationPresent(FeignClient.class)) {
                    clients.add(type);
                }
                queue.add(type);
            }
        }
        return clients;
    }
}
