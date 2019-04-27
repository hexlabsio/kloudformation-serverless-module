package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.module.Module
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.modification
import io.kloudformation.resource.aws.apigateway.BasePathMapping
import io.kloudformation.resource.aws.apigateway.basePathMapping

class HttpBasePathMapping(val basePathMapping: BasePathMapping) : Module {
    class Predefined(var restApiId: Value<String>, var stage: String, var dependsOn: String) : Properties
    class Props(val domain: Value<String>, val basePath: Value<String>?) : Properties
    class Parts {
        val httpBasePathMapping = modification<BasePathMapping.Builder, BasePathMapping, Props>()
    }
    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<HttpBasePathMapping, Parts, Predefined>(pre, Parts()) {
        override fun KloudFormation.buildModule(): Parts.() -> HttpBasePathMapping = {
            val basePathMappingResource = httpBasePathMapping(props) { props ->
                basePathMapping(props.domain, dependsOn = kotlin.collections.listOf(pre.dependsOn)) {
                    props.basePath?.let { basePath(it) }
                    stage(pre.stage)
                    restApiId(pre.restApiId)
                }
            }
            HttpBasePathMapping(basePathMappingResource)
        }
    }
}