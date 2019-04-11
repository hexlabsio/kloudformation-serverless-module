package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.model.KloudFormationTemplate
import io.kloudformation.resource.aws.s3.bucket
import io.kloudformation.toYaml
import org.junit.jupiter.api.Test
import kotlin.test.expect

class ServerlessTest {

    @Test
    fun `should have bucket bucket policy logGroup Role and Function by default`() {
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
        expect("""---
AWSTemplateFormatVersion: "2010-09-09"
Resources:
  Bob:
    Type: "AWS::S3::Bucket"
    Properties:
      BucketName: "Bob"
  LogGroup:
    Type: "AWS::Logs::LogGroup"
    Properties:
      LogGroupName: "/aws/lambda/testService-dev-myFunction"
  Role:
    Type: "AWS::IAM::Role"
    Properties:
      AssumeRolePolicyDocument:
        Statement:
        - Effect: "Allow"
          Action:
          - "sts:AssumeRole"
          Principal:
            Service:
            - "lambda.amazonaws.com"
        Version: "2012-10-17"
      ManagedPolicyArns:
      - Fn::Join:
        - ""
        - - "arn:"
          - Ref: "AWS::Partition"
          - ":iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
      Path: "/"
      Policies:
      - PolicyDocument:
          Statement:
          - Effect: "Allow"
            Action:
            - "logs:CreateLogStream"
            Resource:
            - Fn::Join:
              - ""
              - - "arn:"
                - Ref: "AWS::Partition"
                - ":logs:"
                - Ref: "AWS::Region"
                - ":"
                - Ref: "AWS::AccountId"
                - ":log-group:/aws/lambda/testService-dev-definition:*"
          - Effect: "Allow"
            Action:
            - "logs:PutLogEvents"
            Resource:
            - Fn::Join:
              - ""
              - - "arn:"
                - Ref: "AWS::Partition"
                - ":logs:"
                - Ref: "AWS::Region"
                - ":"
                - Ref: "AWS::AccountId"
                - ":log-group:/aws/lambda/testService-dev-definition:*"
                - ":*"
          Version: "2012-10-17"
        PolicyName: "dev-testService-lambda"
      RoleName:
        Fn::Join:
        - ""
        - - "testService-dev-"
          - Ref: "AWS::Region"
          - "-lambdaRole"
  Function:
    Type: "AWS::Lambda::Function"
    DependsOn:
    - "LogGroup"
    - "Role"
    Properties:
      Code:
        S3Bucket:
          Ref: "Bob"
        S3Key: "Dont know"
      Handler: "a.b.c"
      Role:
        Fn::GetAtt:
        - "Role"
        - "Arn"
      Runtime: "nodejs8.10"
""") { template }
    }

    @Test
    fun `should have bucket and bucket policy by default`() {
            val template = KloudFormationTemplate.create {
                serverless("testService") {
                    serverlessFunction(
                        functionId = "myFunction",
                        codeLocationKey = +"Dont know",
                        handler = +"a.b.c",
                        runtime = +"nodejs8.10"
                    ) {
                        lambdaFunction { tracingConfig { mode("Active") } }
                        http(cors = true) {
                            path({ "abc" / "def" }) {
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
}