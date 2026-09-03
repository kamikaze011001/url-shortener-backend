package com.sonanh.urlshortener.links.web;

import java.time.Instant;

/**
 * Wire shape, fixed by {@code contracts/openapi.yaml}. Renaming a field here is an API
 * change and belongs in the contract first.
 *
 * @param alias null to have a Short Code generated. When present it is claimed exactly
 *              or the request fails — never a silent fallback to a generated code.
 */
record CreateLinkRequest(String destination, String alias, Instant expiresAt) {}
