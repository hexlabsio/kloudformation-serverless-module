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
import io.kloudformation.module.OptionalModification
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModules
import io.kloudformation.module.builder
import io.kloudformation.module.optionalModification
import io.kloudformation.property.aws.iam.role.policy
import io.kloudformation.resource.aws.iam.Role
import io.kloudformation.resource.aws.iam.role
import java.util.UUID

class Serverless(val globalRole: Role?, val functions: List<ServerlessFunction>) : Module {

    open class PrivateConfig(val securityGroups: Value<List<Value<String>>>? = null, val subnetIds: Value<List<Value<String>>>? = null)
    object NoPrivateConfig : PrivateConfig()

    class Parts(
        val globalRole: OptionalModification<Role.Builder, Role, RoleProps> = optionalModification(absent = false)
    ) {
        val serverlessFunction = SubModules({ pre: ServerlessFunction.Predefined, props: ServerlessFunction.Props -> ServerlessFunction.Builder(pre, props) })
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
        data class RoleProps(var assumedRolePolicyDocument: PolicyDocument) : Properties
    }

    class Builder(
        val serviceName: String,
        val stage: String,
        val bucketName: Value<String>,
        val privateConfig: PrivateConfig? = null
    ) : ModuleBuilder<Serverless, Parts>(Parts()) {

        override fun KloudFormation.buildModule(): Parts.() -> Serverless = {
            val roleResource = roleFor(serviceName, stage, globalRole)
            val functions = serverlessFunction.modules().mapNotNull {
                it.module(ServerlessFunction.Predefined(serviceName, stage, bucketName, roleResource, privateConfig))()
            }
            Serverless(roleResource, functions)
        }

        companion object {
            fun KloudFormation.roleFor(serviceName: String, stage: String, roleMod: OptionalModification<Role.Builder, Role, Parts.RoleProps>): Role? {
                val defaultAssumeRole = policyDocument(version = IamPolicyVersion.V2.version) {
                    statement(action = action("sts:AssumeRole")) {
                        principal(PrincipalType.SERVICE, listOf(+"lambda.amazonaws.com"))
                    }
                }
                val logResource = +"arn:" + awsPartition + ":logs:" + awsRegion + ":" + awsAccountId + ":log-group:/aws/lambda/$serviceName-$stage-definition:*"
                return roleMod(Serverless.Parts.RoleProps(defaultAssumeRole)) { props ->
                    role(props.assumedRolePolicyDocument) {
                        policies(listOf(
                                policy(
                                        policyName = +"$stage-$serviceName-${UUID.randomUUID()}",
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

fun KloudFormation.serverless(
    serviceName: String,
    stage: String = "dev",
    bucketName: Value<String>,
    privateConfig: Serverless.PrivateConfig? = null,
    partBuilder: Serverless.Parts.() -> Unit = {}
) = builder(Serverless.Builder(serviceName, stage, bucketName, privateConfig), partBuilder)