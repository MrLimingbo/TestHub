package io.testhub.platform.functional;

import java.time.Instant;

public record FunctionalDefectLink(String id, String defectId, String defectCode, String defectTitle, String assetType,
                                   String assetId, String assetLabel, String createdBy, Instant createdAt) { }
