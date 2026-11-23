package org.openapitools.codegen.languages;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenType;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.openapitools.codegen.CodegenConstants.X_IMPLEMENTS;
import static org.openapitools.codegen.CodegenConstants.X_IS_ONE_OF_INTERFACE;
import static org.openapitools.codegen.utils.StringUtils.camelize;

/**
 * <p>Mustache templates are located in {@code src/main/resources/php-laravel/}.
 */
public class PhpLaravelServerCodegen extends AbstractPhpCodegen implements CodegenConfig {
    private static final String AUTOWIRE = "autowire";
    public static final String USE_ONE_OF_INTERFACES = "useOneOfInterfaces";
    private final Logger LOGGER = LoggerFactory.getLogger(PhpLaravelServerCodegen.class);

    public CodegenType getTag() {
        return CodegenType.SERVER;
    }

    public String getName() {
        return "php-laravel";
    }

    public String getHelp() {
        return "Generates a php-laravel server.";
    }

    protected String controllerDirName = "Http\\Controllers";
    protected String controllerPackage;
    protected String routesFileName = "routes.php";
    protected String routesDirectory = "";

    public PhpLaravelServerCodegen() {
        super();

        outputFolder = "generated-code" + File.separator + "php-laravel";
        embeddedTemplateDir = templateDir = "php-laravel";
        variableNamingConvention = "camelCase";

        // We need inline enums to be resolved to a separate model so that
        // anyOf/oneOf that contain them work correctly.
        inlineSchemaOption.put("RESOLVE_INLINE_ENUMS", "true");

        modelDocTemplateFiles.clear();
        apiDocTemplateFiles.clear();
        apiTestTemplateFiles.clear();
        modelTestTemplateFiles.clear();

        setSrcBasePath("");
        setInvokerPackage("OpenAPI\\Server");

        additionalProperties.put("controllerPackage", controllerPackage);
        additionalProperties.put("controllerSrcPath", "." + "/" + toSrcPath(controllerPackage, srcBasePath));

        apiTemplateFiles.put("api_controller.mustache", ".php");

        supportingFiles.add(new SupportingFile("README.mustache", "", "README.md"));
        supportingFiles.add(new SupportingFile("composer.mustache", "", "composer.json"));
        supportingFiles.add(new SupportingFile("phpunit.mustache", "", "phpunit.xml.dist"));

        typeMapping.put("file", "\\Illuminate\\Http\\UploadedFile");
        languageSpecificPrimitives.add("\\Illuminate\\Http\\UploadedFile");

        cliOptions.add(new CliOption(AUTOWIRE, "Should autowire be enabled.").defaultValue("false"));
        cliOptions.add(CliOption.newBoolean(USE_ONE_OF_INTERFACES, "Generate an interface for each oneOf schema with a discriminator, implemented by its member models and annotated with a crell/serde type map. oneOf schemas without a discriminator keep the default class model.", useOneOfInterfaces));
    }

    @Override
    public void processOpts() {
        super.processOpts();

        convertPropertyToBooleanAndWriteBack(USE_ONE_OF_INTERFACES, this::setUseOneOfInterfaces);
        vendorExtensions.put("x-autowire", additionalProperties.getOrDefault(AUTOWIRE, "false").equals("true"));

        supportingFiles.add(new SupportingFile("routes.mustache", routesDirectory, routesFileName));
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);

        if (openAPI == null || openAPI.getPaths() == null) {
            return;
        }

        openAPI.getPaths()
                .values()
                .stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .filter(path -> path.getResponses() != null)
                .flatMap(path -> path.getResponses().entrySet().stream())
                .forEach(respEntry -> {
                    String httpStatusCode = respEntry.getKey();
                    ApiResponse apiResponse = respEntry.getValue();

                    // If schema is present in the response
                    if (apiResponse.getContent() != null && !apiResponse.getContent().isEmpty()) {
                        return;
                    }

                    // Create an empty schema
                    Schema<?> noContentSchema = new Schema<>()
                            .type("object")                     // Mark it explicitly as an object
                            .description("No content for " + httpStatusCode);

                    Map<String, Schema> props = new HashMap<>();
                    props.put("dummy", new Schema<>().type("string").nullable(true).description("dummy property for no-content responses"));
                    noContentSchema.setProperties(props);

                    // Give it a unique name, e.g. "NoContent204", "NoContent404", etc.
                    String modelName = "NoContent" + camelize(httpStatusCode);

                    // Make sure components and schemas exist
                    if (openAPI.getComponents() == null) {
                        openAPI.setComponents(new Components());
                    }
                    if (openAPI.getComponents().getSchemas() == null) {
                        openAPI.getComponents().setSchemas(new HashMap<>());
                    }

                    // Register our schema in components
                    openAPI.getComponents().getSchemas().put(modelName, noContentSchema);

                    // Now force the response to reference that named schema
                    String ref = "#/components/schemas/" + modelName;
                    Content forcedContent = new Content()
                            .addMediaType("application/json", new MediaType().schema(new Schema<>().$ref(ref)));
                    apiResponse.setContent(forcedContent);
                });
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        if (useOneOfInterfaces) {
            keepOnlyDiscriminatedOneOfInterfaces(result);
        }
        return result;
    }

    private void keepOnlyDiscriminatedOneOfInterfaces(Map<String, ModelsMap> objs) {
        List<CodegenModel> models = objs.values().stream()
                .flatMap(modelsMap -> modelsMap.getModels().stream())
                .map(ModelMap::getModel)
                .collect(Collectors.toList());

        Set<String> interfaces = new HashSet<>();
        for (CodegenModel model : models) {
            if (Boolean.TRUE.equals(model.vendorExtensions.get(X_IS_ONE_OF_INTERFACE))) {
                if (model.discriminator == null) {
                    model.vendorExtensions.remove(X_IS_ONE_OF_INTERFACE);
                } else {
                    interfaces.add(model.classname);
                }
            }
        }

        for (CodegenModel model : models) {
            Object implemented = model.vendorExtensions.get(X_IMPLEMENTS);
            if (implemented == null) {
                continue;
            }
            Collection<?> declared = implemented instanceof Collection ? (Collection<?>) implemented : Collections.singletonList(implemented);
            List<Object> retained = declared.stream().filter(interfaces::contains).collect(Collectors.toList());
            if (retained.isEmpty()) {
                model.vendorExtensions.remove(X_IMPLEMENTS);
            } else {
                model.vendorExtensions.put(X_IMPLEMENTS, retained);
            }
        }
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        objs = super.postProcessOperationsWithModels(objs, allModels);

        OperationMap operations = objs.getOperations();
        operations.put("controllerName", toControllerName(operations.getPathPrefix()));

        operations.getOperation().forEach(operation -> {
            operation.vendorExtensions.put("x-route-name", operations.getPathPrefix() + "." + operation.operationIdSnakeCase.replace('_', '.'));
        });

        return objs;
    }

    @Override
    public void setInvokerPackage(String invokerPackage) {
        super.setInvokerPackage(invokerPackage);
        apiPackage = invokerPackage + "\\" + apiDirName;
        modelPackage = invokerPackage + "\\" + modelDirName;
        controllerPackage = invokerPackage + "\\" + controllerDirName;
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        String suffix = apiTemplateFiles().get(templateName);
        if (templateName.equals("api_controller.mustache")) {
            return controllerFileFolder() + File.separator + toControllerName(tag) + suffix;
        }

        return apiFileFolder() + File.separator + toApiFilename(tag) + suffix;
    }

    public String controllerFileFolder() {
        return (outputFolder + File.separator + toSrcPath(controllerPackage, srcBasePath));
    }

    @Override
    public String toApiName(String name) {
        return camelize(name) + "ApiInterface";
    }

    protected String toControllerName(String name) {
        return camelize(name) + "Controller";
    }

    @Override
    protected String getParameterDataType(Parameter parameter, Schema schema) {
        if (parameter.get$ref() != null) {
            String refName = ModelUtils.getSimpleRef(parameter.get$ref());
            return toModelName(refName);
        }
        return null;
    }

    @Override
    public String getSchemaType(Schema p) {
        return super.getSchemaType(p);
    }
}
