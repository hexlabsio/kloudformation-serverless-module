package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.function.plus
import io.kloudformation.property.aws.lambda.function.Code
import io.kloudformation.resource.aws.iam.Role
import io.kloudformation.resource.aws.lambda.Function
import io.kloudformation.resource.aws.lambda.function
import io.kloudformation.resource.aws.logs.LogGroup
import io.kloudformation.resource.aws.logs.logGroup
import io.kloudformation.module.Module
import io.kloudformation.module.NoProps
import io.kloudformation.module.Parts
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.modification
import io.kloudformation.module.optionalModification
import io.kloudformation.module.submodule
import io.kloudformation.unaryPlus

data class ServerlessFunction(val logGroup: LogGroup, val role: Role?, val function: Function, val http: Http?, val websocket: WebSocket?) : Module {

    class Predefined(var serviceName: String, var stage: String, var deploymentBucketArn: Value<String>, var globalRole: Role?, var privateConfig: Serverless.PrivateConfig?, val lazyWebsocketInfo: () -> Pair<String, Value<String>>) : Properties()
    sealed class Props(val functionId: String, val handler: Value<String>, val runtime: Value<String>, val privateConfig: Serverless.PrivateConfig? = null) : Properties() {
        class BucketLocationProps(val codeLocationKey: Value<String>, functionId: String, handler: Value<String>, runtime: Value<String>, privateConfig: Serverless.PrivateConfig? = null) : Props(functionId, handler, runtime, privateConfig)
        class CodeProps(val code: Value<String>, functionId: String, handler: Value<String>, runtime: Value<String>, privateConfig: Serverless.PrivateConfig? = null) : Props(functionId, handler, runtime, privateConfig)
    }

    class Parts : io.kloudformation.module.Parts() {
        data class LambdaProps(var code: Code, var handler: Value<String>, var role: Value<String>, var runtime: Value<String>) : Properties()
        val lambdaLogGroup = modification<LogGroup.Builder, LogGroup, NoProps>()
        val lambdaRole = optionalModification<Role.Builder, Role, Serverless.Parts.RoleProps>(absent = true)
        val lambdaFunction = modification<Function.Builder, Function, LambdaProps>()
        val http = submodule { pre: Http.Predefined, props: Http.Props -> Http.Builder(pre, props) }

        val websocket = submodule { pre: WebSocket.Predefined, props: WebSocket.Props -> WebSocket.Builder(pre, props) }
        fun http(
            cors: Path.CorsConfig,
            vpcEndpoint: Value<String>? = null,
            authorizerArn: Value<String>? = null,
            modifications: Http.Parts.(Http.Predefined) -> Unit = {}
        ) = http(Http.Props(cors, vpcEndpoint, authorizerArn), modifications)
        fun http(
            cors: Boolean = false,
            vpcEndpoint: Value<String>? = null,
            authorizerArn: Value<String>? = null,
            modifications: Http.Parts.(Http.Predefined) -> Unit = {}
        ) = http(Http.Props(if (cors) Path.CorsConfig() else null, vpcEndpoint, authorizerArn), modifications)
        fun websocket(authorizerArn: Value<String>? = null, modifications: WebSocket.Parts.(WebSocket.Predefined) -> Unit = {}) {
            websocket(WebSocket.Props(authorizerArn), modifications)
        }
    }

    class Builder(
        pre: Predefined,
        val props: Props
    ) : SubModuleBuilder<ServerlessFunction, Parts, Predefined>(pre, Parts()) {

        private fun String.normalize() = replace(Regex("[^a-zA-Z]"), "").capitalize()

        override fun KloudFormation.buildModule(): Parts.() -> ServerlessFunction = {
            val logGroupResource = lambdaLogGroup(NoProps) {
                logGroup(logicalName = "LogGroup" + props.functionId.normalize()) {
                    logGroupName(+"/aws/lambda/${pre.serviceName}-${pre.stage}-${props.functionId}")
                    modifyBuilder(it)
                }
            }
            val code = when (props) {
                is Props.BucketLocationProps -> Code(s3Bucket = pre.deploymentBucketArn, s3Key = props.codeLocationKey)
                is Props.CodeProps -> Code(zipFile = props.code)
            }
            if (pre.globalRole == null) lambdaRole.keep()
            val roleResource = Serverless.Builder.run { roleFor(pre.serviceName, pre.stage, props.functionId, lambdaRole) } ?: pre.globalRole
            val lambdaResource = lambdaFunction(Parts.LambdaProps(code, props.handler, roleResource?.Arn() ?: +"", props.runtime)) { props ->
                function(
                        props.code,
                        props.handler,
                        props.role,
                        props.runtime,
                        logicalName = "Function" + this@Builder.props.functionId.normalize(),
                        dependsOn = listOfNotNull(logGroupResource.logicalName, roleResource?.logicalName)) {
                    val privateOverride = this@Builder.props.privateConfig
                    if (privateOverride !is Serverless.NoPrivateConfig) {
                        (privateOverride ?: pre.privateConfig)?.let { config ->
                            if (config.securityGroups != null && config.subnetIds != null) {
                                vpcConfig(config.securityGroups, config.subnetIds)
                            }
                        }
                    }
                    modifyBuilder(props)
                }
            }
            val http = build(http, Http.Predefined(pre.serviceName, pre.stage, lambdaResource.Arn()))
            val websocket = build(websocket, WebSocket.Predefined(
                    pre.serviceName,
                    pre.stage,
                    lambdaResource.logicalName,
                    lambdaResource.Arn(),
                    pre.lazyWebsocketInfo
            ))
            ServerlessFunction(logGroupResource, roleResource, lambdaResource, http, websocket)
        }
    }
}