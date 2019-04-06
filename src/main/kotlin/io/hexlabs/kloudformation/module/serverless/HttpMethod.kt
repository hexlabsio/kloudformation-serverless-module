package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.module.Modification
import io.kloudformation.module.Module
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.modification
import io.kloudformation.resource.aws.apigateway.method

enum class Method {
    CONNECT,
    DELETE,
    GET,
    HEAD,
    OPTIONS,
    PATCH,
    POST,
    PUT,
    TRACE
}

class HttpMethod(val method: io.kloudformation.resource.aws.apigateway.Method, val corsEnabled: Boolean) : Module {

    class Predefined(var cors: Boolean, var restApiId: Value<String>, var resourceId: Value<String>, var integrationUri: Value<String>) : Properties

    class Props(val httpMethod: Value<String>) : Properties

    class Parts(
        val httpMethod: Modification<io.kloudformation.resource.aws.apigateway.Method.Builder, io.kloudformation.resource.aws.apigateway.Method, MethodProps> = modification()
    ) {
        class MethodProps(var httpMethod: Value<String>) : Properties
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<HttpMethod, Parts, Predefined>(pre, Parts()) {
        override fun KloudFormation.buildModule(): Parts.() -> HttpMethod = {
            val method = httpMethod(Parts.MethodProps(props.httpMethod)) { props ->
                method(props.httpMethod, pre.resourceId, pre.restApiId) {
                    requestParameters(emptyMap())
                    apiKeyRequired(false)
                    authorizationType("None")
                    integration {
                        integrationHttpMethod("POST")
                        type("AWS_PROXY")
                        uri(pre.integrationUri)
                    }
                    methodResponses(emptyList())
                    modifyBuilder(props)
                }
            }
            HttpMethod(method, pre.cors)
        }
    }
}