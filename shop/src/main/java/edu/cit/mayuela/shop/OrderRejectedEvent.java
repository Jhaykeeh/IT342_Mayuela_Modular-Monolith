package edu.cit.mayuela.shop;

public record OrderRejectedEvent(Long orderId, String reason) {
}