package com.sonanh.urlshortener.identity.web;

import java.time.Instant;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. Never carries the hash. */
record OwnerResponse(String id, String email, Instant createdAt) {}
