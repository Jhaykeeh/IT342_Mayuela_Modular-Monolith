package edu.cit.mayuela.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural rules for the Tiangge channel, checked against the compiled
 * classes rather than the source, so they cannot be talked around.
 *
 * All three rules are about the boundary the lab asks for: the marketplace is
 * something the application talks to, not something the domain knows about.
 *
 * Runs without a Spring context and without a database.
 */
class ChannelArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("edu.cit.mayuela");
    }

    @Test
    @DisplayName("the Order and Inventory modules do not depend on the channel")
    void orderAndInventoryDoNotDependOnChannel() {
        noClasses()
                .that()
                .resideInAPackage("edu.cit.mayuela.shop..")
                .or()
                .resideInAPackage("edu.cit.mayuela.inventory..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("edu.cit.mayuela.channel..")
                .because("the marketplace is an outbound adapter and the domain must not know about it")
                .check(classes);
    }

    @Test
    @DisplayName("no module outside the channel can see a channel type")
    void channelTypesAreNotVisibleElsewhere() {
        noClasses()
                .that()
                .resideOutsideOfPackage("edu.cit.mayuela.channel..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("edu.cit.mayuela.channel..")
                .because("ChannelStatus is the channel's only public door, and every other type is hidden")
                .check(classes);
    }

    @Test
    @DisplayName("channel types are package-private, interfaces excepted")
    void channelTypesAreHidden() {
        classes()
                .that()
                .resideInAPackage("edu.cit.mayuela.channel..")
                .and()
                .areNotInterfaces()
                .should()
                .bePackagePrivate()
                .because("only interfaces may be public, so nothing Tiangge-shaped can escape the package")
                .check(classes);
    }
}