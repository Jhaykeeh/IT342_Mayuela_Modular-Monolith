package edu.cit.mayuela.shop;

public record LowStockEvent(String productId, int remainingStock) {
}