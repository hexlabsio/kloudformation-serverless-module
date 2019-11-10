package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.Value
import io.kloudformation.function.Att
import io.kloudformation.function.Reference
import io.kloudformation.model.KloudFormationTemplate
import io.kloudformation.module.value
import io.kloudformation.property.aws.lambda.function.Code
import io.kloudformation.resource.aws.apigateway.BasePathMapping
import io.kloudformation.resource.aws.apigateway.Resource
import io.kloudformation.resource.aws.apigateway.RestApi
import io.kloudformation.resource.aws.apigatewayv2.Api
import io.kloudformation.resource.aws.iam.Role
import io.kloudformation.resource.aws.lambda.Function
import io.kloudformation.resource.aws.lambda.Permission
import io.kloudformation.resource.aws.logs.LogGroup
import io.kloudformation.resource.aws.s3.bucket
import io.kloudformation.resource.aws.sns.Subscription
import io.kloudformation.resource.aws.sns.Topic
import io.kloudformation.toYaml
import io.kloudformation.unaryPlus
import org.junit.jupiter.api.Test
import kotlin.test.expect

class ServerlessTest {

    private inline fun <reified T> KloudFormationTemplate.filter() = resources.resources.toList().filter { it.second is T }.map { it.first to (it.second as T) }

    @Test
    fun `should have logGroup Role and Function by default`() {
        val template = KloudFormationTemplate.create {
            val myBucket = bucket("Bob") { bucketName("Bob") }
            serverless("testService", bucketName = myBucket.ref()) {
                serverlessFunction(functionId = "myFunction", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10")
            }
        }
        expect(1) { template.filter<Role>().size }
        expect(1) { template.filter<LogGroup>().size }
        expect(1) { template.filter<Function>().size }
    }

    @Test
    fun `should have bucket and bucket policy by default`() {
            val template = KloudFormationTemplate.create {
                serverless("testService", bucketName = +"bucket") {
                    serverlessFunction(
                        functionId = "myFunction",
                        codeLocationKey = +"Dont know",
                        handler = +"a.b.c",
                        runtime = +"nodejs8.10"
                    ) {
                        lambdaFunction { tracingConfig { mode("Active") } }
                        http(cors = false) {
                            path({ "abc" / "def" }) {
                                cors {
                                    it.origin = +"test"
                                }
                                Method.GET()
                                Method.POST()
                                path({ "ghi" / { "uvw" } }) {
                                    Method.POST()
                                }
                            }
                        }
                    }
                }
        }
        println(template.toYaml())
    }

    @Test
    fun `should use global role for both functions`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunction(functionId = "myFunction", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10")
                serverlessFunction(functionId = "myFunction2", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10")
            }
        }
        val roles = template.filter<Role>()
        expect(1) { roles.size }
    }

    @Test
    fun `should use global role for one and local role for another function`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunction(functionId = "myFunction", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10")
                serverlessFunction(functionId = "myFunction2", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10") {
                    lambdaRole {
                        println("dkfajsdlk")
                    }
                }
            }
        }
        val roles = template.filter<Role>()
        expect(2) { roles.size }
    }
    @Test
    fun `should use local roles only`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunction(functionId = "myFunction", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10") {
                    lambdaRole {}
                }
                serverlessFunction(functionId = "myFunction2", codeLocationKey = +"Dont know", handler = +"a.b.c", runtime = +"nodejs8.10") {
                    lambdaRole {}
                }
            }
        }
        val roles = template.filter<Role>()
        expect(2) { roles.size }
    }

    @Test
    fun `should allow code function`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code")
            }
        }
        val function = template.filter<Function>().first()
        expect(Code(zipFile = Value.Of("Some Code"))) { function.second.code }
    }

    @Test
    fun `should link methods to rest api when empty path`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    http(cors = false) {
                        path("/") {
                            Method.GET()
                        }
                    }
                }
            }
        }
        val restApi = template.filter<RestApi>().first()
        val resources = template.filter<Resource>()
        val method = template.filter<io.kloudformation.resource.aws.apigateway.Method>().first()
        expect(0) { resources.size }
        expect(Att(restApi.first, Value.Of("RootResourceId"))) { method.second.resourceId }
    }

    @Test
    fun `should create base path mapping`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    http {
                        httpBasePathMapping(+"a.b.com", +"dev")
                    }
                }
            }
        }
        val restApi = template.filter<RestApi>().first().second
        val basePathMapping = template.filter<BasePathMapping>().first().second
        expect(Value.Of("a.b.com")) { basePathMapping.domainName }
        expect(restApi.ref().ref) { (basePathMapping.restApiId as Reference<String>).ref }
        expect(Value.Of("dev")) { basePathMapping.stage }
    }

    @Test
    fun `should create single websocket api`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {

                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    websocket {
                        routeMapping(+"\$connect")
                    }
                }
                serverlessFunctionWithCode(functionId = "myFunction2", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    websocket {
                        routeMapping(+"\$connect")
                        routeMapping(+"\$disconnect")
                    }
                }
            }
        }
        println(template.toYaml())
        expect(1) { template.filter<Api>().size }
    }

    @Test
    fun `should create topic permission and subscription`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    sns()
                }
            }
        }
        println(template.toYaml())
        expect(1) { template.filter<Topic>().size }
        expect(1) { template.filter<Permission>().size }
        expect(1) { template.filter<Subscription>().size }
    }

    @Test
    fun `should create 2 topics permissions and subscriptions`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    sns()
                    sns()
                }
            }
        }
        println(template.toYaml())
        expect(2) { template.filter<Topic>().size }
        expect(2) { template.filter<Permission>().size }
        expect(2) { template.filter<Subscription>().size }
    }

    @Test
    fun `should create permission and subscription and not topic when provided`() {
        val template = KloudFormationTemplate.create {
            serverless("testService", bucketName = +"bucket") {
                serverlessFunctionWithCode(functionId = "myFunction", handler = +"a.b.c", runtime = +"nodejs8.10", code = +"Some Code") {
                    sns {
                        snsTopic.replaceWith(+"OtherTopic")
                    }
                }
            }
        }
        println(template.toYaml())
        expect(0) { template.filter<Topic>().size }
        expect(1) { template.filter<Permission>().size }
        expect(1) { template.filter<Subscription>().size }
        template.filter<Permission>().first().second.let {
            expect("OtherTopic") { it.sourceArn?.value() }
        }
        template.filter<Subscription>().first().second.let {
            expect("OtherTopic") { it.topicArn.value() }
        }
    }
}