package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsAccountId
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsPartition
import io.kloudformation.model.KloudFormationTemplate.Builder.Companion.awsRegion
import io.kloudformation.model.iam.IamPolicyVersion
import io.kloudformation.model.iam.PolicyDocument
import io.kloudformation.model.iam.PolicyStatement
import io.kloudformation.model.iam.PrincipalType
import io.kloudformation.model.iam.action
import io.kloudformation.model.iam.policyDocument
import io.kloudformation.model.iam.resource
import io.kloudformation.module.Module
import io.kloudformation.module.ModuleBuilder
import io.kloudformation.module.NoProps
import io.kloudformation.module.OptionalModification
import io.kloudformation.module.Properties
import io.kloudformation.module.builder
import io.kloudformation.module.modification
import io.kloudformation.module.optionalModification
import io.kloudformation.module.submodules
import io.kloudformation.property.aws.iam.role.Policy
import io.kloudformation.resource.aws.apigatewayv2.Api
import io.kloudformation.resource.aws.apigatewayv2.Deployment
import io.kloudformation.resource.aws.apigatewayv2.Stage
import io.kloudformation.resource.aws.apigatewayv2.api
import io.kloudformation.resource.aws.apigatewayv2.deployment
import io.kloudformation.resource.aws.apigatewayv2.stage
import io.kloudformation.resource.aws.iam.Role
import io.kloudformation.resource.aws.iam.role
import io.kloudformation.unaryPlus
import java.util.UUID

class Serverless(val globalRole: Role?, val globalWebsocketApi: Api?, val functions: List<ServerlessFunction>, val websocketStage: Stage?, val websocketDeployment: Deployment?) : Module {

    open class PrivateConfig(val securityGroups: Value<List<Value<String>>>? = null, val subnetIds: Value<List<Value<String>>>? = null)
    object NoPrivateConfig : PrivateConfig()

    class Parts(
        val globalRole: OptionalModification<Role.Builder, Role, RoleProps> = optionalModification(absent = false)
    ) : io.kloudformation.module.Parts() {
        val globalWebsocketApi = modification<Api.Builder, Api, NoProps>()
        val serverlessFunction = submodules { pre: ServerlessFunction.Predefined, props: ServerlessFunction.Props -> ServerlessFunction.Builder(pre, props) }
        val websocketStage = modification<Stage.Builder, Stage, NoProps>()
        val websocketDeployment = modification<Deployment.Builder, Deployment, NoProps>()
        fun serverlessFunction(
            functionId: String,
            codeLocationKey: Value<String>,
            handler: Value<String>,
            runtime: Value<String>,
            privateConfig: Serverless.PrivateConfig? = null,
            modifications: ServerlessFunction.Parts.(ServerlessFunction.Predefined) -> Unit = {}
        ) = serverlessFunction(ServerlessFunction.Props.BucketLocationProps(codeLocationKey, functionId, handler, runtime, privateConfig), modifications)
        fun serverlessFunctionWithCode(
            functionId: String,
            code: Value<String>,
            handler: Value<String>,
            runtime: Value<String>,
            privateConfig: Serverless.PrivateConfig? = null,
            modifications: ServerlessFunction.Parts.(ServerlessFunction.Predefined) -> Unit = {}
        ) = serverlessFunction(ServerlessFunction.Props.CodeProps(code, functionId, handler, runtime, privateConfig), modifications)
        data class RoleProps(var assumedRolePolicyDocument: PolicyDocument) : Properties()
    }

    class Builder(
        val serviceName: String,
        val stage: String,
        val bucketName: Value<String>,
        val privateConfig: PrivateConfig? = null,
        val routeSelectionExpression: Value<String> = +"\$request.body.action"
    ) : ModuleBuilder<Serverless, Parts>(Parts()) {

        override fun KloudFormation.buildModule(): Parts.() -> Serverless = {
            var roleResource = roleFor(serviceName, stage, "lambda", globalRole)
            var websocketApi: Api? = null
            val lazyWebsocketApi = {
                if (websocketApi == null) {
                    websocketApi = globalWebsocketApi(NoProps) {
                        api(+stage + serviceName + "-websockets", +"WEBSOCKET", routeSelectionExpression) {
                            modifyBuilder(it)
                        }
                    }
                }
                websocketApi!!.logicalName to websocketApi!!.ref()
            }
            val functions = serverlessFunction.modules().mapNotNull {
                build(it, ServerlessFunction.Predefined(serviceName, stage, bucketName, roleResource, privateConfig, lazyWebsocketApi))
            }
            if (functions.all { it.role != roleResource }) {
                roleResource?.let { this@buildModule.resources.remove(it) }
            }
            val routes = functions.mapNotNull { it.websocket?.routes }.flatten().map { it.route.logicalName }
            var deployment: Deployment? = null
            var websocketStage: Stage? = null
            websocketApi?.let { api ->
                deployment = websocketDeployment(NoProps) {
                    deployment(
                            apiId = api.ref(),
                            logicalName = "WebsocketsDeployment${UUID.randomUUID().toString().replace("-", "")}",
                            dependsOn = routes
                    ) {
                        modifyBuilder(it)
                    }
                }
                websocketStage = websocketStage(NoProps) {
                    stage(api.ref(), deployment!!.ref(), +stage) {
                        modifyBuilder(it)
                    }
                }
            }
            Serverless(roleResource, websocketApi, functions, websocketStage, deployment)
        }

        companion object {
            fun KloudFormation.roleFor(serviceName: String, stage: String, name: String, roleMod: OptionalModification<Role.Builder, Role, Parts.RoleProps>): Role? {
                val defaultAssumeRole = policyDocument(version = IamPolicyVersion.V2.version) {
                    statement(action = action("sts:AssumeRole")) {
                        principal(PrincipalType.SERVICE, listOf(+"lambda.amazonaws.com"))
                    }
                }
                val logResource = +"arn:" + awsPartition + ":logs:" + awsRegion + ":" + awsAccountId + ":log-group:/aws/lambda/$serviceName-$stage-definition:*"
                return roleMod(Serverless.Parts.RoleProps(defaultAssumeRole)) { props ->
                    role(props.assumedRolePolicyDocument) {
                        policies(listOf(
                                Policy(
                                        policyName = +"$stage-$serviceName-$name",
                                        policyDocument = PolicyDocument(
                                                version = IamPolicyVersion.V2.version,
                                                statement = listOf(
                                                        PolicyStatement(
                                                                action = action("logs:CreateLogStream"),
                                                                resource = resource(logResource)
                                                        ),
                                                        PolicyStatement(
                                                                action = action("logs:PutLogEvents"),
                                                                resource = resource(logResource + ":*")
                                                        )
                                                )
                                        )
                                )

                        ))
                        path("/")
                        managedPolicyArns(listOf(
                                +"arn:" + awsPartition + ":iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
                        ))
                        modifyBuilder(props)
                    }
                }
            }
        }
    }
}

fun Role.withWebsocketPolicy(name: String) = copy(policies = policies.orEmpty() + Policy(
            policyName = +name,
            policyDocument = PolicyDocument(
                    version = IamPolicyVersion.V2.version,
                    statement = listOf(PolicyStatement(
                        action = action("execute-api:ManageConnections"),
                        resource = resource("arn:aws:execute-api:*:*:*/@connections/*")
                    ))
            )
    ))

fun KloudFormation.serverless(
    serviceName: String,
    stage: String = "dev",
    bucketName: Value<String>,
    privateConfig: Serverless.PrivateConfig? = null,
    routeSelectionExpression: Value<String> = +"\$request.body.action",
    partBuilder: Serverless.Parts.() -> Unit = {}
) = builder(Serverless.Builder(serviceName, stage, bucketName, privateConfig, routeSelectionExpression), partBuilder)