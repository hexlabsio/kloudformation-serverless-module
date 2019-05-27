package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsPartition
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsRegion
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsUrlSuffix
import io.kloudformation.module.NoProps
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.Module
import io.kloudformation.module.modification
import io.kloudformation.module.optionalModification
import io.kloudformation.module.submodules
import io.kloudformation.resource.aws.apigatewayv2.authorizer
import io.kloudformation.resource.aws.apigatewayv2.Authorizer
import io.kloudformation.resource.aws.apigatewayv2.Integration
import io.kloudformation.resource.aws.apigatewayv2.integration
import io.kloudformation.resource.aws.lambda.Permission
import io.kloudformation.resource.aws.lambda.permission
import io.kloudformation.unaryPlus

class WebSocket(val integration: Integration, val permission: Permission, val routes: List<WebSocketRoute>, val authorizer: Authorizer?, val authorizerPermission: Permission?) : Module {
    class Predefined(
        var serviceName: String,
        var stage: String,
        var lambdaLogicalName: String,
        var lambdaArn: Value<String>,
        val lazyWebsocketInfo: () -> Pair<String, Value<String>>
    ) : Properties

    class Props(val authorizerArn: Value<String>? = null) : Properties

    class Parts {
        val websocketIntegration = modification<Integration.Builder, Integration, NoProps>()
        val websocketAuthorizer = optionalModification<Authorizer.Builder, Authorizer, AuthorizerProps>()
        val authorizerPermission = optionalModification<Permission.Builder, Permission, NoProps>()
        val lambdaPermission = modification<Permission.Builder, Permission, NoProps>()
        val routeMapping = submodules { pre: WebSocketRoute.Predefined, props: WebSocketRoute.Props -> WebSocketRoute.Builder(pre, props) }
        fun routeMapping(routeKey: Value<String>, modifications: WebSocketRoute.Parts.(WebSocketRoute.Predefined) -> Unit = {}) {
            routeMapping(WebSocketRoute.Props(routeKey), modifications)
        }
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<WebSocket, Parts, Predefined>(pre, Parts()) {

        override fun KloudFormation.buildModule(): Parts.() -> WebSocket = {
            val (logicalName, apiId) = pre.lazyWebsocketInfo()
            val websocketIntegrationResource = websocketIntegration(NoProps) {
                integration(apiId, +"AWS_PROXY") {
                    integrationUri(+"arn:" + awsPartition + ":apigateway:" + awsRegion + ":lambda:path/2015-03-31/functions/" + pre.lambdaArn + "/invocations")
                    modifyBuilder(it)
                }
            }
            val authorizerResource = props.authorizerArn?.let { authorizerArn ->
                websocketAuthorizer(AuthorizerProps(Value.Of(0), listOf(authorizerArn), +"route.request.querystring.Authorizer")) { authProps ->
                    authorizer(
                            apiId = apiId,
                            authorizerType = +"REQUEST",
                            identitySource = +listOf(authProps.identitySource),
                            authorizerUri = +"arn:" + awsPartition + ":apigateway:" + awsRegion + ":lambda:path/2015-03-31/functions/" + authProps.providerArns.first() + "/invocations",
                            name = +"websocket-auth-${pre.serviceName}"
                    ) {
                        modifyBuilder(authProps)
                    }
                }
            }
            val authorizerPermissionResource = props.authorizerArn?.let { authorizerArn ->
                authorizerPermission(NoProps) {
                    permission(
                            action = +"lambda:InvokeFunction",
                            functionName = authorizerArn,
                            principal = +"apigateway." + awsUrlSuffix,
                            dependsOn = listOf(logicalName)) {
                        modifyBuilder(it)
                    }
                }
            }
            val permissionResource = lambdaPermission(NoProps) {
                permission(
                        action = +"lambda:InvokeFunction",
                        functionName = pre.lambdaArn,
                        principal = +"apigateway." + awsUrlSuffix,
                        dependsOn = listOf(pre.lambdaLogicalName, logicalName)) {
                    modifyBuilder(it)
                }
            }
            val routes = routeMapping.modules().mapNotNull {
                it.module(WebSocketRoute.Predefined(apiId, +"integrations/" + websocketIntegrationResource.ref(), authorizerResource?.ref()))()
            }
            WebSocket(websocketIntegrationResource, permissionResource, routes, authorizerResource, authorizerPermissionResource)
        }
    }
}