package org.naruprof.proof;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import org.junit.jupiter.api.Test;

class PyramidTest {
    @Test void inwardDependenciesAndPrivateAdapters() {
        var classes = new ClassFileImporter().importPackages("org.naruprof");
        noClasses().that().resideInAnyPackage("..infra..","..api..","..ports..","..learning..")
                .should().dependOnClassesThat().resideInAnyPackage("..runtime..","..jpa..","..http..","..grpc..",
                        "net.thevpc..","org.springframework..","jakarta.persistence..","io.grpc..").check(classes);
        noClasses().that().resideInAnyPackage("..http..","..grpc..")
                .should().dependOnClassesThat().resideInAnyPackage("..learning..","..ports..","..runtime..","..jpa..").check(classes);
        noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().resideInAPackage("..ports..").check(classes);
    }
}
