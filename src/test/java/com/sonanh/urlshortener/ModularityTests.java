package com.sonanh.urlshortener;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * ADR-0012: module boundaries fail the build rather than rot in a document.
 *
 * <p>This is the most valuable test in the repository. Everything else verifies
 * behaviour we could re-check by hand; this verifies a structural claim that is
 * otherwise impossible to notice breaking.
 *
 * <p>What it does <b>not</b> prove: Modulith checks code dependencies, never data
 * dependencies. Two modules querying the same table pass this test while carrying a
 * real coupling. That is why {@code redirect} goes through
 * {@link com.sonanh.urlshortener.links.LinkLookup} instead of reading the table.
 */
class ModularityTests {

	static final ApplicationModules MODULES = ApplicationModules.of(UrlShortenerApplication.class);

	@Test
	void verifiesModularStructure() {
		MODULES.verify();
	}

	@Test
	void writesDocumentationSnippets() {
		new Documenter(MODULES)
				.writeModulesAsPlantUml()
				.writeIndividualModulesAsPlantUml();
	}
}
