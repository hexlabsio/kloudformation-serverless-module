package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsAccountId
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsPartition
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsRegion
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsUrlSuffix
import io.kloudformation.model.Output
import io.kloudformation.model.iam.ConditionKey
import io.kloudformation.model.iam.ConditionOperators
import io.kloudformation.model.iam.IamPolicyVersion
import io.kloudformation.model.iam.action
import io.kloudformation.model.iam.policyDocument
import io.kloudformation.model.iam.resource
import io.kloudformation.module.NoProps
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.Module
import io.kloudformation.module.modification
import io.kloudformation.module.submodule
import io.kloudformation.module.submodules
import io.kloudformation.resource.aws.apigateway.Deployment
import io.kloudformation.resource.aws.apigateway.RestApi
import io.kloudformation.resource.aws.apigateway.deployment
import io.kloudformation.resource.aws.apigateway.restApi
import io.kloudformation.resource.aws.lambda.Permission
import io.kloudformation.resource.aws.lambda.permission
import java.util.UUID

class Http(val restApi: RestApi, val paths: List<Path>, val deployment: Deployment, val permission: Permission, val basePathMapping: HttpBasePathMapping?) : Module {
    class Predefined(var serviceName: String, var stage: String, var lambdaArn: Value<String>) : Properties
    class Props(val cors: Path.CorsConfig? = null, val vpcEndpoint: Value<String>? = null) : Properties
    class BasePathProps(val domain: Value<String>, val basePath: Value<String>? = null) : Properties

    class Parts {
        val httpRestApi = modification<RestApi.Builder, RestApi, NoProps>()
        val httpDeployment = modification<Deployment.Builder, Deployment, NoProps>()
        val lambdaPermission = modification<Permission.Builder, Permission, NoProps>()
        val httpBasePathMapping = submodule { pre: HttpBasePathMapping.Predefined, props: HttpBasePathMapping.Props -> HttpBasePathMapping.Builder(pre, props) }
        fun httpBasePathMapping(
            domain: Value<String>,
            basePath: Value<String>? = null,
            modifications: HttpBasePathMapping.Parts.(HttpBasePathMapping.Predefined) -> Unit = {}
        ) = httpBasePathMapping(HttpBasePathMapping.Props(domain, basePath), modifications)
        val path = submodules { pre: Path.Predefined, props: Path.Props -> Path.Builder(pre, props) }
        fun path(
            pathBuilder: Path.PathBuilder.() -> Path.PathBuilder = { this },
            modifications: Path.Parts.(Path.Predefined) -> Unit = {}
        ) = path(Path.Props(pathBuilder), modifications)
        fun path(
            path: String,
            modifications: Path.Parts.(Path.Predefined) -> Unit = {}
        ) = path(Path.Props(path), modifications)
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<Http, Parts, Predefined>(pre, Parts()) {

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
            outputs("ServiceEndpoint" to Output(
                    description = "URL of the service",
                    value = +"https://" + restApiResource.ref() + ".execute-api." + awsRegion + "." + awsUrlSuffix + "/${pre.stage}"
            ))
            val lambdaIntegration = +"arn:" + awsPartition + ":apigateway:" + awsRegion + ":lambda:path/2015-03-31/functions/" + pre.lambdaArn + "/invocations"
            val paths = path.modules().mapNotNull {
                it.module(Path.Predefined(
                        parentId = restApiResource.RootResourceId(),
                        restApi = restApiResource,
                        integrationUri = lambdaIntegration,
                        cors = props.cors?.let { Path.CorsConfig() }))()
            }
            fun subPaths(path: Path): List<Path> = listOf(path) + path.subPaths.flatMap { subPaths(it) }
            val allMethods = paths.flatMap { subPaths(it) }.flatMap { it.methods }.map { it.method.logicalName }
            val deployment = httpDeployment(NoProps) {
                deployment(
                        restApiId = restApiResource.ref(),
                        logicalName = "ApiDeployment${UUID.randomUUID().toString().replace("-","")}",
                        dependsOn = allMethods
                ) {
                    stageName(pre.stage)
                    modifyBuilder(it)
                }
            }
            val httpBasePathMapping = httpBasePathMapping.module(HttpBasePathMapping.Predefined(restApiResource.ref(), pre.stage, deployment.logicalName))()
            val lambdaPermissionResource = lambdaPermission(NoProps) {
                permission(
                        action = +"lambda:InvokeFunction",
                        functionName = pre.lambdaArn,
                        principal = +"apigateway." + awsUrlSuffix
                ) {
                    sourceArn(+"arn:" + awsPartition + ":execute-api:" + awsRegion + ":" + awsAccountId + ":" + restApiResource.ref() + "/*/*")
                    modifyBuilder(it)
                }
            }
            Http(restApiResource, paths, deployment, lambdaPermissionResource, httpBasePathMapping)
        }
    }
}