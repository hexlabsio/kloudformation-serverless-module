package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.module.Modification
import io.kloudformation.resource.aws.apigateway.Resource
import io.kloudformation.resource.aws.apigateway.resource
import io.kloudformation.module.Module
import io.kloudformation.module.OptionalModification
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.modification
import io.kloudformation.module.optionalModification
import io.kloudformation.module.submodules
import io.kloudformation.property.aws.apigateway.method.integrationResponse
import io.kloudformation.property.aws.apigateway.method.methodResponse
import io.kloudformation.resource.aws.apigateway.RestApi
import io.kloudformation.resource.aws.apigateway.method
import io.kloudformation.unaryPlus

class Path(val resource: Map<String, Resource>, val subPaths: List<Path>, val methods: List<HttpMethod>) : Module {
    data class CorsConfig(
        var origin: Value<String> = +"'*'",
        var headers: Value<String> = +"'Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent'",
        var credentials: Value<String> = +"'false'",
        var methods: Value<String> = +"'*'"
    ) : Properties()
    class PathBuilder(val pathParts: List<String> = emptyList()) {
        operator fun div(path: String) = PathBuilder(pathParts + path)
        operator fun div(parameter: () -> String) = PathBuilder(pathParts + "{${parameter()}}")
        operator fun String.div(path: String) = PathBuilder(listOf(this, path))
        operator fun String.div(parameter: () -> String) = PathBuilder(listOf(this, "{${parameter()}}"))
        operator fun (() -> String).div(path: String) = PathBuilder(listOf("{${this()}}", path))
        operator fun (() -> String).div(parameter: () -> String) = PathBuilder(listOf("{${this()}}", "{${parameter()}}"))
    }
    class Predefined(var parentId: Value<String>, var restApi: RestApi, var integrationUri: Value<String>, var cors: Boolean, var authProps: AuthProps?) : Properties()

    class Props(pathBuilder: PathBuilder.() -> PathBuilder = { this }) : Properties() {
        constructor(path: String) : this({ PathBuilder(if (path.isEmpty() || path == "/") emptyList() else (if (path.startsWith("/")) path.substring(1) else path).split("/")) })
        val pathParts: List<String> = pathBuilder(PathBuilder()).pathParts
    }

    class Parts(
        val httpResource: Map<String, Modification<Resource.Builder, Resource, ResourceProps>> = emptyMap(),
        val cors: OptionalModification<CorsConfig, CorsConfig, CorsConfig> = optionalModification(absent = true)
    ) : io.kloudformation.module.Parts() {
        val httpMethod = submodules { pre: HttpMethod.Predefined, props: HttpMethod.Props -> HttpMethod.Builder(pre, props) }
        fun httpMethod(
            httpMethod: String,
            modifications: HttpMethod.Parts.(HttpMethod.Predefined) -> Unit = {}
        ) = httpMethod(HttpMethod.Props(httpMethod), modifications)
        fun httpMethod(
            httpMethod: Method,
            modifications: HttpMethod.Parts.(HttpMethod.Predefined) -> Unit = {}
        ) = httpMethod(httpMethod.name, modifications)
        operator fun Method.invoke(
            modifications: HttpMethod.Parts.(HttpMethod.Predefined) -> Unit = {}
        ) = httpMethod(name, modifications)
        val path = submodules { pre: Path.Predefined, props: Path.Props -> Path.Builder(pre, props) }
        fun path(
            pathBuilder: Path.PathBuilder.() -> Path.PathBuilder = { this },
            modifications: Path.Parts.(Path.Predefined) -> Unit = {}
        ) = path(Path.Props(pathBuilder), modifications)
        fun path(
            path: String,
            modifications: Path.Parts.(Path.Predefined) -> Unit = {}
        ) = path(Path.Props(path), modifications)

        class ResourceProps(var path: Value<String>, var parentId: Value<String>, var restApi: Value<String>) : Properties()
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<Path, Parts, Predefined>(pre, Parts(
            httpResource = props.pathParts.map { it to modification<Resource.Builder, Resource, Parts.ResourceProps>() }.toMap()
    )) {
        override fun KloudFormation.buildModule(): Parts.() -> Path = {
            val pathParts = props.pathParts
            var normalizedPath = ""
            fun append(part: String) { normalizedPath += part.let { (if (it.contains("{")) it.substringAfter("{").substringBeforeLast("}") + "Var" else it).replace(Regex("[^0-9A-Za-z]"), "") } }
            val apiResources = pathParts.foldIndexed(emptyList<Pair<String, Resource>>()) { index, acc, pathPart ->
                val parentId = if (index == 0) pre.parentId else acc.last().second.ref()
                acc + (pathPart to httpResource[pathPart]!!(Parts.ResourceProps(Value.Of(pathPart), parentId, pre.restApi.ref())) { props ->
                    append(pathPart)
                    resource(parentId = props.parentId, restApiId = props.restApi, pathPart = props.path, logicalName = allocateLogicalName("ApiGatewayResource$normalizedPath")) {
                        modifyBuilder(props)
                    }
                })
            }.toMap()
            val endResource: Value<String> = apiResources.toList().lastOrNull()?.second?.ref() ?: pre.restApi.RootResourceId()
            val apiMethods = httpMethod.modules().mapNotNull {
                build(it, HttpMethod.Predefined(pre.cors, pre.restApi.ref(), endResource, pre.integrationUri, normalizedPath, pre.authProps))
            }
            val corsMethodsForPath = apiMethods.map { it.method.httpMethod }
            val methodsValue: Value<String> = when {
                corsMethodsForPath.isEmpty() -> +"''"
                corsMethodsForPath.all { it is Value.Of<*> } -> +("'" + corsMethodsForPath.map { (it as Value.Of<String>).value }.reduce { acc, method -> "$acc,$method" } + "'")
                else -> +"'" + corsMethodsForPath.reduce { acc, method -> acc + "," + method } + "'"
            }
            var optionsMethod: HttpMethod? = null
            if (pre.cors) cors.keep()
            cors(CorsConfig(methods = methodsValue)) {
                val corsConfig = it.modifyBuilder(it)
                val corsOrigin = "method.response.header.Access-Control-Allow-Origin"
                val corsHeaders = "method.response.header.Access-Control-Allow-Headers"
                val corsMethods = "method.response.header.Access-Control-Allow-Methods"
                val corsCredentials = "method.response.header.Access-Control-Allow-Credentials"
                val method = method(+"OPTIONS", endResource, pre.restApi.ref(), logicalName = allocateLogicalName("Method${normalizedPath}Options")) {
                    authorizationType("NONE")
                    methodResponses(listOf(
                            methodResponse(statusCode = +"200") {
                                responseParametersMap(mapOf(corsOrigin to true, corsHeaders to true, corsMethods to true, corsCredentials to true))
                                responseModels(emptyMap())
                            }
                    ))
                    requestParameters(emptyMap())
                    integration {
                        type("MOCK")
                        requestTemplatesMap(mapOf("application/json" to "{statusCode:200}"))
                        contentHandling("CONVERT_TO_TEXT")
                        integrationResponses(listOf(
                                integrationResponse(+"200") {
                                    responseParameters(mapOf(
                                            corsOrigin to corsConfig.origin,
                                            corsHeaders to corsConfig.headers,
                                            corsMethods to corsConfig.methods,
                                            corsCredentials to corsConfig.credentials
                                    ))
                                    responseTemplatesMap(mapOf(
                                            "application/json" to "#set(\$origin = \$input.params(\"Origin\"))\n#if(\$origin == \"\") #set(\$origin = \$input.params(\"origin\")) #end\n#if(\$origin == \"*\") #set(\$context.responseOverride.header.Access-Control-Allow-Origin = \$origin) #end"
                                    ))
                                }
                        ))
                    }
                }
                optionsMethod = HttpMethod(method, true)
                corsConfig
            }
            val paths = path.modules().mapNotNull {
                build(it, Path.Predefined(endResource, pre.restApi, pre.integrationUri, pre.cors, pre.authProps))
            }
            Path(apiResources, paths, apiMethods + (optionsMethod?.let { listOf(it) } ?: emptyList()))
        }
    }
}