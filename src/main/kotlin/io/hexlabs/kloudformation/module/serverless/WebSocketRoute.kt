package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.module.Module
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.modification
import io.kloudformation.resource.aws.apigatewayv2.Route
import io.kloudformation.resource.aws.apigatewayv2.route

class WebSocketRoute(val route: Route) : Module {
    class Predefined(var websocketApiId: Value<String>, var target: Value<String>) : Properties
    class Props(val routeKey: Value<String>) : Properties
    class Parts {
        val websocketRoute = modification<Route.Builder, Route, Props>()
    }
    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<WebSocketRoute, Parts, Predefined>(pre, Parts()) {
        override fun KloudFormation.buildModule(): Parts.() -> WebSocketRoute = {
            val routeResource = websocketRoute(props) {
                route(pre.websocketApiId, it.routeKey) {
                    authorizationType("NONE")
                    target(pre.target)
                    modifyBuilder(it)
                }
            }
            WebSocketRoute(routeResource)
        }
    }
}