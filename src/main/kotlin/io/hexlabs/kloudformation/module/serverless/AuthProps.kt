package io.hexlabs.kloudformation.module.serverless

import io.kloudformation.Value
import io.kloudformation.module.Properties

data class AuthProps(val authType: Value<String>, val authId: Value<String>)

class AuthorizerProps(var resultTtl: Value<Int>, var providerArns: List<Value<String>>, val identitySource: Value<String>) : Properties
