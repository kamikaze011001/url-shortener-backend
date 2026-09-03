package com.sonanh.urlshortener.shared;

/**
 * The only exception a use case should throw for an expected failure.
 *
 * <p>Carries a {@link ProblemCode}, which decides both the HTTP status and the
 * machine-readable {@code code} the frontend switches on. Anything else reaching the
 * handler becomes an opaque {@code INTERNAL} — stack traces never leave the process.
 */
public class ApiException extends RuntimeException {

	private final ProblemCode code;

	public ApiException(ProblemCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ProblemCode code() {
		return code;
	}

	public static ApiException notFound(String detail) {
		return new ApiException(ProblemCode.NOT_FOUND, detail);
	}

	public static ApiException aliasTaken(String alias) {
		return new ApiException(ProblemCode.ALIAS_TAKEN, "The alias '" + alias + "' is already in use.");
	}
}
