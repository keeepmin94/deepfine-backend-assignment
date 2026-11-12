package com.deepfine.inventory.api;

public record ApiError(String code, String message, String path) {
}
