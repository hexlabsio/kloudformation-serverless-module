package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsAccountId
import io.kloudformation.model.iam.ConditionKey
import io.kloudformation.model.iam.ConditionOperators
import io.kloudformation.model.iam.IamPolicyVersion
import io.kloudformation.model.iam.action
import io.kloudformation.model.iam.policyDocument
import io.kloudformation.model.iam.resource
import io.kloudformation.module.NoProps
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.SubModules
import io.kloudformation.module.Module
import io.kloudformation.module.modification
import io.kloudformation.resource.aws.apigateway.RestApi
import io.kloudformation.resource.aws.apigateway.restApi

class Http(val restApi: RestApi, val paths: List<Path>) : Module {
    class Predefined(var serviceName: String, var stage: String) : Properties
    class Props(val cors: CorsConfig? = null, val vpcEndpoint: Value<String>? = null) : Properties

    class CorsConfig

    class Parts {
        val httpRestApi = modification<RestApi.Builder, RestApi, NoProps>()
        val path = SubModules({ pre: Path.Predefined, props: Path.Props -> Path.Builder(pre, props) })
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<Http, Parts, Predefined, Props>(pre, Parts()) {

        override fun KloudFormation.buildModule(): Parts.() -> Http = {
            val restApiResource = httpRestApi(NoProps) {
                restApi {
                    name("${pre.stage}-${pre.serviceName}")
                    if (props.vpcEndpoint != null) {
                        endpointConfiguration {
                            types(listOf(+"PRIVATE"))
                        }
                        policy(policyDocument(version = IamPolicyVersion.V2.version) {
                            statement(
                                    action = action("execute-api:Invoke"),
                                    resource = resource(+"arn:aws:execute-api:us-east-1:" + awsAccountId + ":*")
                            ) {
                                allPrincipals()
                                condition(ConditionOperators.stringEquals, mapOf(
                                        ConditionKey<String>("aws:sourceVpce") to listOf(props.vpcEndpoint)
                                ))
                            }
                        })
                    }
                    modifyBuilder(it)
                }
            }
            val paths = path.modules().mapNotNull {
                it.module(Path.Predefined(restApiResource.RootResourceId(), restApiResource.ref()))()
            }
            Http(restApiResource, paths)
        }
    }
}