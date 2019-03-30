package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.model.KloudFormationTemplate
import io.kloudformation.resource.aws.s3.bucket
import io.kloudformation.toYaml
import org.junit.jupiter.api.Test

class ServerlessTest {

    @Test
    fun `should have bucket and bucket policy by default`() {
        val template = KloudFormationTemplate.create {
            val myBucket = bucket("Bob") { bucketName("Bob") }
            serverless("testService", bucketArn = myBucket.ref()) {
                serverlessFunction(ServerlessFunction.Props(
                        functionId = "myFunction",
                        codeLocationKey = +"Dont know",
                        handler = +"a.b.c",
                        runtime = +"nodejs8.10")) {
                }
            } }.toYaml()
        println(template)
    }
}