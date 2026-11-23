package org.openapitools.codegen.php.laravel;

import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.TestUtils;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.languages.PhpLaravelServerCodegen;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PhpLaravelServerCodegenTest {

    /**
     * Parameters whose spec (wire) name is not already a valid camelCase PHP identifier must be read
     * from the request and validated under the wire name ({@code baseName}), not under the sanitized
     * PHP variable name ({@code paramName}). Otherwise the generated controller reads the wrong key
     * and the value is always null.
     */
    @Test
    public void shouldUseWireNameForRequestLookupsAndValidationKeys() throws Exception {
        Map<String, Object> properties = new HashMap<>();

        File output = Files.createTempDirectory("test").toFile();

        final CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("php-laravel")
                .setAdditionalProperties(properties)
                .setInputSpec("src/test/resources/3_0/php-laravel/petstore-with-fake-endpoints-models-for-testing.yaml")
                .setOutputDir(output.getAbsolutePath().replace("\\", "/"));

        final ClientOptInput clientOptInput = configurator.toClientOptInput();
        DefaultGenerator generator = new DefaultGenerator();
        List<File> files = generator.opts(clientOptInput).generate();

        TestUtils.ensureContainsFile(files, output, "Http/Controllers/FakeController.php");
        TestUtils.ensureContainsFile(files, output, "Http/Controllers/StoreController.php");
        TestUtils.ensureContainsFile(files, output, "Http/Controllers/UserController.php");

        Path fakeController = files.stream().filter(f -> f.getName().equals("FakeController.php")).findFirst().orElseThrow().toPath();

        // request accessors use the wire name (snake_case) while the local variable keeps the sanitized name
        TestUtils.assertFileContains(fakeController, "$filterClientName = $request->string('filter_client_name')->value();");
        TestUtils.assertFileContains(fakeController, "$filterUserIds = $request->get('filter_user_ids');");
        TestUtils.assertFileContains(fakeController, "$clientId = $request->integer('client_id');");

        // validation rule keys must match $request->all(), i.e. the wire name
        TestUtils.assertFileContains(fakeController, "'filter_client_name' => [");
        TestUtils.assertFileContains(fakeController, "'filter_user_ids' => [");
        TestUtils.assertFileContains(fakeController, "'client_id' => [");

        // the buggy behaviour (reading under the sanitized identifier) must not reappear
        TestUtils.assertFileNotContains(fakeController, "$request->string('filterClientName')");
        TestUtils.assertFileNotContains(fakeController, "$request->get('filterUserIds')");
        TestUtils.assertFileNotContains(fakeController, "$request->integer('clientId')");

        // path parameters: the merged validation key and the rule key both use the wire name
        Path storeController = files.stream().filter(f -> f.getName().equals("StoreController.php")).findFirst().orElseThrow().toPath();
        TestUtils.assertFileContains(storeController, "'order_id' => $orderId,");
        TestUtils.assertFileContains(storeController, "'order_id' => [");

        // body parameters are out of scope and must stay byte-identical: no wire-name substitution for the request body
        Path userController = files.stream().filter(f -> f.getName().equals("UserController.php")).findFirst().orElseThrow().toPath();
        TestUtils.assertFileContains(userController, "$user = $request->get('user');");

        output.deleteOnExit();
    }

    @Test
    public void shouldParseUseOneOfInterfacesOptionDisabledByDefault() {
        PhpLaravelServerCodegen codegen = new PhpLaravelServerCodegen();
        codegen.processOpts();
        assertFalse(codegen.getUseOneOfInterfaces());
        assertTrue(codegen.cliOptions().stream().anyMatch(option -> option.getOpt().equals(PhpLaravelServerCodegen.USE_ONE_OF_INTERFACES)));

        codegen = new PhpLaravelServerCodegen();
        codegen.additionalProperties().put(PhpLaravelServerCodegen.USE_ONE_OF_INTERFACES, "true");
        codegen.processOpts();
        assertTrue(codegen.getUseOneOfInterfaces());
    }

    @Test
    public void shouldGenerateInterfacesForDiscriminatedOneOfWhenEnabled() throws Exception {
        Map<String, Path> models = generateOneOfInterfacesSpec(Map.of(PhpLaravelServerCodegen.USE_ONE_OF_INTERFACES, "true"));

        TestUtils.assertFileContains(models.get("OwnerPet.php"),
                "#[Serde\\StaticTypeMap(key: 'pet_type', map: [\n    'cat' => Cat::class,\n    'dog' => Dog::class,\n])]\ninterface OwnerPet\n{\n}");
        TestUtils.assertFileContains(models.get("Owner.php"), "public \\OpenAPI\\Server\\Model\\OwnerPet $pet,");
        TestUtils.assertFileContains(models.get("Cat.php"), "class Cat implements OwnerPet, Pet\n");
        TestUtils.assertFileContains(models.get("Dog.php"), "class Dog implements OwnerPet, Pet\n");

        TestUtils.assertFileContains(models.get("Shape.php"), "'circle' => Circle::class,", "interface Shape\n");
        TestUtils.assertFileContains(models.get("Circle.php"), "class Circle implements Shape\n");

        // without a discriminator mapping (legacyDiscriminatorBehavior=true) there is nothing to build a type map from
        TestUtils.assertFileContains(models.get("Pet.php"), "interface Pet\n");
        TestUtils.assertFileNotContains(models.get("Pet.php"), "StaticTypeMap");

        // oneOf without a discriminator keeps the default class model
        TestUtils.assertFileContains(models.get("OwnerNickname.php"), "class OwnerNickname\n");
        TestUtils.assertFileNotContains(models.get("OwnerNickname.php"), "interface OwnerNickname");
        TestUtils.assertFileNotContains(models.get("Cat.php"), "OwnerNickname");

        // x-implements declared in the spec is not PHP specific and stays ignored
        TestUtils.assertFileNotContains(models.get("Dog.php"), "java.io.Serializable");
    }

    @Test
    public void shouldKeepOneOfAsClassWhenDisabled() throws Exception {
        Map<String, Path> models = generateOneOfInterfacesSpec(new HashMap<>());

        models.forEach((fileName, model) -> TestUtils.assertFileNotContains(model,
                "interface " + fileName.replace(".php", ""), " implements ", "StaticTypeMap"));
        TestUtils.assertFileContains(models.get("OwnerPet.php"), "class OwnerPet\n");
    }

    private Map<String, Path> generateOneOfInterfacesSpec(Map<String, Object> properties) throws Exception {
        File output = Files.createTempDirectory("test").toFile();
        output.deleteOnExit();

        final CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("php-laravel")
                .setAdditionalProperties(properties)
                .setInputSpec("src/test/resources/3_0/php-laravel/oneof-interfaces.yaml")
                .setOutputDir(output.getAbsolutePath().replace("\\", "/"));

        List<File> files = new DefaultGenerator().opts(configurator.toClientOptInput()).generate();

        return files.stream()
                .filter(file -> file.getParentFile().getName().equals("Model"))
                .collect(Collectors.toMap(File::getName, File::toPath));
    }
}
