package io.testhub.platform.uiautomation;

/** A bounded declarative browser operation. User JavaScript is intentionally not part of this runner contract. */
public record UiAutomationStep(String id, String type, String name, String selector, String pageObjectId,
                               String value, String url, String expectedText, Integer timeoutMillis) { }
