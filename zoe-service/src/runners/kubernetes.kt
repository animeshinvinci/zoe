// Copyright (c) 2020 Adevinta.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.adevinta.oss.zoe.service.runners

import com.adevinta.oss.zoe.core.FailureResponse
import com.adevinta.oss.zoe.core.utils.*
import com.adevinta.oss.zoe.service.utils.doAsync
import com.adevinta.oss.zoe.service.utils.loadFileFromResources
import com.adevinta.oss.zoe.service.utils.userError
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import io.fabric8.kubernetes.client.Watcher
import kotlinx.coroutines.future.await
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class KubernetesRunner(
    override val name: String,
    private val client: NamespacedKubernetesClient,
    private val executor: ExecutorService,
    private val closeClientAtShutdown: Boolean,
    private val configuration: Config
) : ZoeRunner {

    constructor(
        name: String,
        configuration: Config,
        namespace: String,
        context: String?,
        executor: ExecutorService
    ) : this(
        name = name,
        client = kotlin.run {
            val client =
                if (context != null) DefaultKubernetesClient(io.fabric8.kubernetes.client.Config.autoConfigure(context))
                else DefaultKubernetesClient()

            client.inNamespace(namespace)
        },
        executor = executor,
        closeClientAtShutdown = true,
        configuration = configuration
    )

    data class Config(
        val deletePodsAfterCompletion: Boolean,
        val zoeImage: String,
        val cpu: String,
        val memory: String,
        val timeoutMs: Long?
    )

    private val responseFile = "/output/response.txt"
    private val labels = mapOf(
        "owner" to "zoe",
        "runnerId" to uuid()
    )

    override suspend fun launch(function: String, payload: String): String {
        val pod = generatePodObject(
            image = configuration.zoeImage,
            args = listOf(
                mapOf(
                    "function" to function,
                    "payload" to payload.toJsonNode()
                ).toJsonString(),
                responseFile
            )
        )

        return try {
            executor
                .doAsync { client.pods().create(pod) }
                .waitForResponse(timeoutMs = configuration.timeoutMs)
        } finally {
            if (configuration.deletePodsAfterCompletion) {
                executor.doAsync {
                    client
                        .pods()
                        .withName(pod.metadata.name)
                        .withGracePeriod(0)
                        .delete()
                }
            }
        }
    }

    override fun close() {
        if (closeClientAtShutdown) {
            client.use { doClose() }
        } else {
            doClose()
        }
    }

    private fun doClose() {
        // remove any dangling pod
        if (configuration.deletePodsAfterCompletion) {
            logger.debug("deleting potentially dangling pods...")
            client
                .pods()
                .withLabels(labels)
                .withGracePeriod(0)
                .delete()
        }
    }

    private fun generatePodObject(image: String, args: List<String>): Pod {
        val pod = loadFileFromResources("pod.template.json")?.parseJson<Pod>() ?: userError("pod template not found !")
        return pod.apply {
            metadata.name = "zoe-${UUID.randomUUID()}"
            metadata.labels = labels

            spec.containers.find { it.name == "zoe" }?.apply {
                resources.requests = mapOf(
                    "cpu" to Quantity.parse(configuration.cpu),
                    "memory" to Quantity.parse(configuration.memory)
                )

                setImage(image)
                setArgs(args)
            }
        }
    }

    private suspend fun Pod.waitForResponse(timeoutMs: Long?): String {
        val future = CompletableFuture<String>()
        val watch = executor.doAsync {
            client
                .pods()
                .withName(metadata.name)
                .watch(object : Watcher<Pod> {

                    override fun onClose(cause: KubernetesClientException?) {
                        if (!future.isDone) {
                            future.completeExceptionally(
                                ZoeRunnerException(
                                    "pod watcher closed unexpectedly...",
                                    cause = cause,
                                    runnerName = name,
                                    remoteStacktrace = null
                                )
                            )
                        }
                    }

                    override fun eventReceived(action: Watcher.Action, resource: Pod?) {
                        logger.debug("received event '$action' with pod : ${resource?.toJsonString()}")

                        val podState = resource?.status?.phase
                        val zoeState = resource?.status?.containerStatuses?.find { it.name == "zoe" }?.state

                        when {

                            zoeState?.waiting?.reason == "ImagePullBackOff" ->
                                // Means the container image is not pullable. Fail early !
                                future.completeExceptionally(
                                    ZoeRunnerException(
                                        "Zoe image does not seem to be pullable. Pod :${resource.toJsonString()}",
                                        cause = null,
                                        runnerName = name,
                                        remoteStacktrace = null
                                    )
                                )

                            podState == "Pending" -> {
                                logger.debug("pod is spinning up...")
                            }

                            zoeState == null ->
                                future.completeExceptionally(
                                    ZoeRunnerException(
                                        "State for container 'zoe' not found. Pod :${resource?.toJsonString()}",
                                        cause = null,
                                        runnerName = name,
                                        remoteStacktrace = null
                                    )
                                )

                            zoeState.terminated != null ->
                                try {
                                    val response =
                                        client
                                            .pods()
                                            .withName(resource.metadata.name)
                                            .inContainer("tailer")
                                            .file(responseFile)
                                            .read()
                                            .use { it.readAllBytes().toString(Charsets.UTF_8) }

                                    when (zoeState.terminated.exitCode) {
                                        0 -> future.complete(response)
                                        else -> future.completeExceptionally(
                                            response
                                                .runCatching { parseJson<FailureResponse>() }
                                                .map { ZoeRunnerException.fromRunFailureResponse(it, name) }
                                                .getOrElse {
                                                    ZoeRunnerException(
                                                        message = "container exit status : ${zoeState.terminated}",
                                                        cause = null,
                                                        runnerName = name,
                                                        remoteStacktrace = null
                                                    )
                                                }
                                        )
                                    }
                                } catch (err: Throwable) {
                                    future.completeExceptionally(err)
                                }

                            else -> logger.debug("zoe container is in : '${zoeState.toJsonString()}'")
                        }
                    }
                })
        }

        return watch.use {
            future
                .let { if (timeoutMs != null) it.orTimeout(timeoutMs, TimeUnit.MILLISECONDS) else it }
                .await()
        }
    }
}
