package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.module.NoProps
import io.kloudformation.module.Properties
import io.kloudformation.module.SubModuleBuilder
import io.kloudformation.module.Module
import io.kloudformation.module.modification
import io.kloudformation.resource.aws.lambda.Permission
import io.kloudformation.resource.aws.lambda.permission
import io.kloudformation.resource.aws.sns.Subscription
import io.kloudformation.resource.aws.sns.Topic
import io.kloudformation.resource.aws.sns.subscription
import io.kloudformation.resource.aws.sns.topic
import io.kloudformation.unaryPlus

class Sns(val topic: Topic?, val permission: Permission, val subscription: Subscription) : Module {
    class Predefined(var lambdaName: Value<String>, var lambdaArn: Value<String>) : Properties()
    class Props : Properties()
    class Parts : io.kloudformation.module.Parts() {
        val snsTopic = modification<Topic.Builder, Value<String>, NoProps>()
        val snsPermission = modification<Permission.Builder, Permission, NoProps>()
        val snsSubscription = modification<Subscription.Builder, Subscription, NoProps>()
    }

    class Builder(pre: Predefined, val props: Props) : SubModuleBuilder<Sns, Parts, Predefined>(pre, Parts()) {

        override fun KloudFormation.buildModule(): Parts.() -> Sns = {
            var actualTopic: Topic? = null
            val topicResource = snsTopic(NoProps) {
                actualTopic = topic {
                    modifyBuilder(it)
                }
                actualTopic!!.ref()
            }
            val topicPermission = snsPermission(NoProps) {
                permission(+"lambda:InvokeFunction", pre.lambdaName, +"sns.amazonaws.com") {
                    sourceArn(topicResource)
                    modifyBuilder(it)
                }
            }
            val topicSubscription = snsSubscription(NoProps) {
                subscription(+"lambda", topicResource) {
                    endpoint(pre.lambdaArn)
                    modifyBuilder(it)
                }
            }
            Sns(actualTopic, topicPermission, topicSubscription)
        }
    }
}