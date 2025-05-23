package com.loveandlasso.bot.service;

import com.loveandlasso.bot.constant.BotConstants;
import com.loveandlasso.bot.dto.PaymentRequest;
import com.loveandlasso.bot.dto.PaymentResponse;
import com.loveandlasso.bot.model.Payment;
import com.loveandlasso.bot.model.Subscription;
import com.loveandlasso.bot.model.SubscriptionType;
import com.loveandlasso.bot.model.User;
import com.loveandlasso.bot.repository.PaymentRepository;
import com.loveandlasso.bot.repository.UserRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PaymentService {

    private final RestTemplate restTemplate;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    @Value("${payment.yukassa.shopId}")
    private String shopId;

    @Value("${payment.yukassa.secretKey}")
    private String secretKey;

    @Autowired
    public PaymentService(RestTemplate restTemplate,
                          PaymentRepository paymentRepository,
                          UserRepository userRepository,
                          SubscriptionService subscriptionService) {
        this.restTemplate = restTemplate;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
    }

    public PaymentResponse createPayment(@NotNull PaymentRequest paymentRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeCredentials(shopId, secretKey));
        headers.set("Idempotence-Key", UUID.randomUUID().toString());

        Map<String, Object> requestBody = new HashMap<>();

        String amountValue = String.format(Locale.US, "%.2f", paymentRequest.getAmount());
        requestBody.put("amount", Map.of(
                "value", amountValue,
                "currency", paymentRequest.getCurrency()
        ));

        requestBody.put("payment_method_data", Map.of("type", "bank_card"));
        requestBody.put("description", paymentRequest.getDescription());
        requestBody.put("confirmation", Map.of(
                "type", "redirect",
                "return_url", paymentRequest.getReturnUrl()
        ));

        requestBody.put("capture", true);

        Map<String, Object> receipt = new HashMap<>();
        receipt.put("customer", Map.of("email", "customer@example.com"));

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("description", paymentRequest.getDescription());
        item.put("quantity", "1");
        item.put("amount", Map.of(
                "value", amountValue,
                "currency", paymentRequest.getCurrency()
        ));
        item.put("vat_code", 4);
        item.put("payment_subject", "service");
        item.put("payment_mode", "full_payment");
        items.add(item);

        receipt.put("items", items);
        requestBody.put("receipt", receipt);

        requestBody.put("metadata", Map.of(
                "userId", paymentRequest.getUserId().toString(),
                "subscriptionType", paymentRequest.getSubscriptionType().name()
        ));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            Map<String, Object> response = restTemplate.postForObject(
                    "https://api.yookassa.ru/v3/payments",
                    entity,
                    Map.class
            );

            if (response == null) {
                System.err.println("Empty response from YooKassa API");
                return null;
            }

            PaymentResponse paymentResponse = new PaymentResponse();
            paymentResponse.setId((String) response.get("id"));
            paymentResponse.setStatus((String) response.get("status"));

            Map<String, Object> amount = (Map<String, Object>) response.get("amount");
            if (amount != null) {
                paymentResponse.setAmount(Double.parseDouble(amount.get("value").toString()));
                paymentResponse.setCurrency((String) amount.get("currency"));
            }

            Map<String, Object> confirmation = (Map<String, Object>) response.get("confirmation");
            if (confirmation != null) {
                paymentResponse.setPaymentUrl((String) confirmation.get("confirmation_url"));
            }

            savePayment(paymentResponse, paymentRequest);

            return paymentResponse;

        } catch (Exception e) {
            return null;
        }
    }

    public String checkPaymentStatus(String paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encodeCredentials(shopId, secretKey));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    "https://api.yookassa.ru/v3/payments/" + paymentId,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<String, Object> response = responseEntity.getBody();

            if (response != null) {
                return (String) response.get("status");
            }

            return BotConstants.PAYMENT_FAILED;
        } catch (RestClientException e) {
            System.err.println("Error checking payment status: " + e.getMessage());
            return BotConstants.PAYMENT_FAILED;
        }
    }

    @Transactional
    public boolean processSuccessfulPayment(String paymentId) {
        Optional<Payment> paymentOptional = paymentRepository.findByPaymentId(paymentId);

        if (paymentOptional.isEmpty()) {
            return false;
        }

        Payment payment = paymentOptional.get();
        User user = payment.getUser();

        if (BotConstants.PAYMENT_SUCCEEDED.equals(payment.getStatus())) {
            return true;
        }

        String status = checkPaymentStatus(paymentId);

        if (!BotConstants.PAYMENT_SUCCEEDED.equals(status)) {
            payment.setStatus(status);
            paymentRepository.save(payment);
            return false;
        }

        payment.setStatus(BotConstants.PAYMENT_SUCCEEDED);
        paymentRepository.save(payment);

        Map<String, Object> metadata = getPaymentMetadata(paymentId);

        if (metadata == null) {
            System.err.println("Metadata not available for payment: " + paymentId);
            return false;
        }

        String subscriptionTypeStr = (String) metadata.get("subscriptionType");

        if (subscriptionTypeStr == null) {
            System.err.println("Subscription type not found in metadata for payment: " + paymentId);
            return false;
        }

        try {
            Subscription subscription = subscriptionService.createSubscription(
                    user,
                    SubscriptionType.valueOf(subscriptionTypeStr)
            );

            payment.setSubscription(subscription);
            paymentRepository.save(payment);

            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid subscription type: " + subscriptionTypeStr);
            return false;
        }
    }

    @Transactional
    public void savePayment(PaymentResponse paymentResponse, @NotNull PaymentRequest paymentRequest) {
        Optional<User> userOptional = userRepository.findById(paymentRequest.getUserId());

        if (userOptional.isEmpty()) {
            return;
        }

        User user = userOptional.get();

        Payment payment = Payment.builder()
                .user(user)
                .amount(paymentResponse.getAmount())
                .paymentDate(LocalDateTime.now())
                .paymentId(paymentResponse.getId())
                .status(paymentResponse.getStatus())
                .description(paymentRequest.getDescription())
                .paymentMethod("card")
                .build();

        paymentRepository.save(payment);
    }

    @Nullable
    private Map<String, Object> getPaymentMetadata(String paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encodeCredentials(shopId, secretKey));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    "https://api.yookassa.ru/v3/payments/" + paymentId,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<String, Object> response = responseEntity.getBody();

            if (response != null) {
                return (Map<String, Object>) response.get("metadata");
            }

            return null;
        } catch (RestClientException e) {
            System.err.println("Error getting payment metadata: " + e.getMessage());
            return null;
        }
    }

    private String encodeCredentials(String shopId, String secretKey) {
        String credentials = shopId + ":" + secretKey;
        return Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
