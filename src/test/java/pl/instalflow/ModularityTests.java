package pl.instalflow;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModularityTests {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(InstalFlowApplication.class).verify();
    }

    @Test
    void declaresAllModulesAsClosedAndExposesOnlyTheJobsContract() {
        var modules = ApplicationModules.of(InstalFlowApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("identity", "crm", "offers", "jobs", "audit",
                        "background", "documents", "notifications", "shared");
        modules.forEach(module -> assertThat(module.isOpen()).isFalse());
        assertThat(modules.getModuleByName("jobs").orElseThrow()
                .getNamedInterfaces().getByName("api")).isPresent();
    }

    @Test
    void neverExposesJpaEntitiesOrRepositoriesToOtherModules() {
        var modules = ApplicationModules.of(InstalFlowApplication.class);
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("pl.instalflow");
        classes.stream()
                .filter(type -> type.isAnnotatedWith(Entity.class) || type.isAssignableTo(Repository.class))
                .forEach(type -> {
                    var module = modules.getModuleByType(type.getName())
                            .orElseThrow(() -> new AssertionError("No module for " + type.getName()));
                    var moduleType = module.getType(type.getName()).orElseThrow();
                    assertThat(module.isExposed(moduleType))
                            .as("%s must remain internal to %s", type.getName(), module.getIdentifier())
                            .isFalse();
                });
    }

    @Test
    void verifierRejectsARealImportOfAnotherModulesInternalClass() {
        var violations = assertThrows(Violations.class,
                () -> ApplicationModules.of("architecturefixtures.internalaccess", (ImportOption) location -> true).verify());
        assertThat(violations.getMessage()).contains("InternalComponent", "non-exposed");
    }

    @Test
    void verifierRejectsARealCycleEvenWhenBothApisArePublic() {
        var violations = assertThrows(Violations.class,
                () -> ApplicationModules.of("architecturefixtures.cycle", (ImportOption) location -> true).verify());
        assertThat(violations.getMessage()).containsIgnoringCase("cycle")
                .contains("LeftApi", "RightApi");
    }
}
