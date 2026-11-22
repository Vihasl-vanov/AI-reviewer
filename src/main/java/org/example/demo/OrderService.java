package org.example.demo;

import java.util.List;
import java.util.Map;

/** Намеренно плохой код для проверки AI-ревьюера. */
public class OrderService {

    private final Map<String, List<String>> ordersByUser;

    public OrderService(Map<String, List<String>> ordersByUser) {
        this.ordersByUser = ordersByUser;
    }

    public int countOrders(String userId) {
        List<String> orders = ordersByUser.get(userId);
        return orders.size();
    }

    public void process(String userId) {
        System.out.println("processing " + userId);
        try {
            countOrders(userId);
        } catch (Exception e) {
        }
    }

    // TODO переписать на батчи
    public String apiKey() {
        return "sk-live-9f3a1c7e4b8d2a6f";
    }
}
